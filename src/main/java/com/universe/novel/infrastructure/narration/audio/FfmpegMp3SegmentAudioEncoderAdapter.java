package com.universe.novel.infrastructure.narration.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.universe.novel.application.narration.SegmentAudioEncodingRequest;
import com.universe.novel.application.narration.SegmentAudioEncodingResult;
import com.universe.novel.application.ports.SegmentAudioEncoderPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FFmpeg-backed canonical MP3 encoder for narration segment PCM/WAV sources.
 * <p>
 * Encodes to 48kHz mono 96kbps MP3 without ID3v2 metadata and derives the segment's
 * encoded contribution samples from the actual encoded MP3 packet count.
 */
@Component
public class FfmpegMp3SegmentAudioEncoderAdapter implements SegmentAudioEncoderPort {

    static final String OUTPUT_MIME_TYPE = "audio/mpeg";
    static final String CANONICAL_CODEC = "libmp3lame";
    static final String CANONICAL_BITRATE = "96k";
    static final int CANONICAL_SAMPLE_RATE_HZ = 48_000;
    static final int CANONICAL_CHANNELS = 1;
    static final int MP3_FRAME_SAMPLES = 1152;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @FunctionalInterface
    interface TempFileFactory {
        Path create() throws IOException;
    }

    @FunctionalInterface
    interface TempFileDeleter {
        void deleteIfExists(Path path) throws IOException;
    }

    @FunctionalInterface
    interface EncoderProcessRunner {
        EncoderProcessResult execute(List<String> command, InputStream source, Duration timeout)
                throws IOException, InterruptedException, TimeoutException;
    }

    record EncoderProcessResult(int exitCode, String diagnostics) {
        EncoderProcessResult {
            diagnostics = diagnostics == null ? "" : diagnostics;
        }
    }

    record SegmentProbeData(
            String codecName,
            int sampleRateHz,
            int channels,
            int packetCount
    ) {}

    @FunctionalInterface
    interface SegmentAudioProbeRunner {
        SegmentProbeData probe(Path audioFile, Duration timeout)
                throws IOException, InterruptedException, TimeoutException;
    }

    record ProbeProcessResult(int exitCode, String output) {
        ProbeProcessResult {
            output = output == null ? "" : output;
        }
    }

    @FunctionalInterface
    interface ProbeProcessRunner {
        ProbeProcessResult execute(List<String> command, Duration timeout)
                throws IOException, InterruptedException, TimeoutException;
    }

    private final String ffmpegExecutable;
    private final String ffprobeExecutable;
    private final Duration timeout;
    private final TempFileFactory tempFileFactory;
    private final TempFileDeleter tempFileDeleter;
    private final EncoderProcessRunner processRunner;
    private final SegmentAudioProbeRunner probeRunner;

    @Autowired
    public FfmpegMp3SegmentAudioEncoderAdapter(
            @Value("${novel.narration.audio.segment-encoder.ffmpeg.path:${novel.narration.audio.encoder.ffmpeg.path:ffmpeg}}") String ffmpegExecutable,
            @Value("${novel.narration.audio.segment-encoder.ffprobe.path:ffprobe}") String ffprobeExecutable,
            @Value("${novel.narration.audio.segment-encoder.timeout:60s}") Duration timeout
    ) {
        this(
                ffmpegExecutable,
                ffprobeExecutable,
                timeout,
                () -> Files.createTempFile("novel_segment_audio_", ".mp3"),
                Files::deleteIfExists,
                new JvmEncoderProcessRunner(),
                new JvmSegmentAudioProbeRunner(ffprobeExecutable)
        );
    }

    FfmpegMp3SegmentAudioEncoderAdapter(
            String ffmpegExecutable,
            String ffprobeExecutable,
            Duration timeout,
            TempFileFactory tempFileFactory,
            TempFileDeleter tempFileDeleter,
            EncoderProcessRunner processRunner,
            SegmentAudioProbeRunner probeRunner
    ) {
        if (ffmpegExecutable == null || ffmpegExecutable.isBlank()) {
            throw new IllegalArgumentException("ffmpegExecutable must not be blank");
        }
        this.ffmpegExecutable = ffmpegExecutable.trim();
        if (ffprobeExecutable == null || ffprobeExecutable.isBlank()) {
            throw new IllegalArgumentException("ffprobeExecutable must not be blank");
        }
        this.ffprobeExecutable = ffprobeExecutable.trim();
        this.timeout = requirePositiveTimeout(timeout);
        this.tempFileFactory = Objects.requireNonNull(tempFileFactory, "tempFileFactory must not be null");
        this.tempFileDeleter = Objects.requireNonNull(tempFileDeleter, "tempFileDeleter must not be null");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
        this.probeRunner = Objects.requireNonNull(probeRunner, "probeRunner must not be null");
    }

    @Override
    public SegmentAudioEncodingResult encode(SegmentAudioEncodingRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requireWavSource(request.mimeType());

        Path tempFile = createTempFile();
        try {
            EncoderProcessResult processResult;
            try (InputStream sourceStream = openSourceStream(request)) {
                processResult = processRunner.execute(commandFor(tempFile), sourceStream, timeout);
            }

            if (processResult.exitCode() != 0) {
                throw new IllegalStateException(
                        "FFmpeg segment audio encoding failed with exit code " + processResult.exitCode()
                                + diagnosticSuffix(processResult.diagnostics())
                );
            }

            if (!Files.isRegularFile(tempFile)) {
                throw new IllegalStateException("FFmpeg did not produce an encoded segment audio file.");
            }
            long sizeBytes = Files.size(tempFile);
            if (sizeBytes <= 0) {
                throw new IllegalStateException("FFmpeg produced an empty encoded segment audio file.");
            }

            // Probe actual encoded MP3 artifact using authoritative ffprobe packet count
            SegmentProbeData probeData;
            try {
                probeData = probeRunner.probe(tempFile, timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("FFprobe segment audio inspection was interrupted.", e);
            } catch (TimeoutException e) {
                throw new IllegalStateException("FFprobe segment audio inspection timed out after " + timeout.toMillis() + " ms.", e);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to probe encoded segment audio with FFprobe: " + safeMessage(e), e);
            }

            validateProbeData(probeData);

            long encodedContributionSamples = (long) probeData.packetCount() * MP3_FRAME_SAMPLES;
            if (encodedContributionSamples <= 0) {
                throw new IllegalStateException("Calculated encodedContributionSamples must be > 0: " + encodedContributionSamples);
            }

            return new SegmentAudioEncodingResult(
                    new TempFileSegmentAudioEncodedResource(
                            tempFile,
                            OUTPUT_MIME_TYPE,
                            sizeBytes,
                            encodedContributionSamples,
                            CANONICAL_SAMPLE_RATE_HZ,
                            Files::newInputStream,
                            tempFileDeleter::deleteIfExists
                    )
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IllegalStateException failure = new IllegalStateException("FFmpeg segment audio encoding was interrupted.", e);
            deleteTempFileAfterFailure(tempFile, failure);
            throw failure;
        } catch (TimeoutException e) {
            IllegalStateException failure = new IllegalStateException(
                    "FFmpeg segment audio encoding timed out after " + timeout.toMillis() + " ms.",
                    e
            );
            deleteTempFileAfterFailure(tempFile, failure);
            throw failure;
        } catch (IOException e) {
            IllegalStateException failure = new IllegalStateException(
                    "Failed to encode segment audio with FFmpeg: " + safeMessage(e),
                    e
            );
            deleteTempFileAfterFailure(tempFile, failure);
            throw failure;
        } catch (RuntimeException e) {
            deleteTempFileAfterFailure(tempFile, e);
            throw e;
        }
    }

    private List<String> commandFor(Path outputFile) {
        return List.of(
                ffmpegExecutable,
                "-hide_banner",
                "-loglevel", "error",
                "-nostats",
                "-y",
                "-f", "wav",
                "-i", "pipe:0",
                "-map_metadata", "-1",
                "-map_chapters", "-1",
                "-id3v2_version", "0",
                "-vn",
                "-c:a", CANONICAL_CODEC,
                "-b:a", CANONICAL_BITRATE,
                "-ar", String.valueOf(CANONICAL_SAMPLE_RATE_HZ),
                "-ac", String.valueOf(CANONICAL_CHANNELS),
                "-f", "mp3",
                outputFile.toAbsolutePath().toString()
        );
    }

    private static void requireWavSource(String mimeType) {
        if (mimeType == null) {
            throw new IllegalArgumentException("Unsupported segment audio encoding source MIME type: null");
        }
        String normalized = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!isWavMime(normalized)) {
            throw new IllegalArgumentException("Unsupported segment audio encoding source MIME type: " + mimeType);
        }
    }

    private static boolean isWavMime(String normalized) {
        return "audio/wav".equals(normalized)
                || "audio/x-wav".equals(normalized)
                || "audio/wave".equals(normalized);
    }

    private static InputStream openSourceStream(SegmentAudioEncodingRequest request) {
        try {
            InputStream sourceStream = request.openStream();
            if (sourceStream == null) {
                throw new IllegalStateException("SegmentAudioEncodingRequest returned a null stream.");
            }
            return sourceStream;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open segment audio source stream.", e);
        }
    }

    private Path createTempFile() {
        try {
            return Objects.requireNonNull(tempFileFactory.create(), "tempFileFactory returned null");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create encoded segment audio temporary file.", e);
        }
    }

    private void deleteTempFileAfterFailure(Path tempFile, RuntimeException primaryFailure) {
        try {
            tempFileDeleter.deleteIfExists(tempFile);
        } catch (IOException | RuntimeException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private static void validateProbeData(SegmentProbeData probeData) {
        if (probeData == null) {
            throw new IllegalStateException("FFprobe returned null probe data.");
        }
        if (!"mp3".equalsIgnoreCase(probeData.codecName())) {
            throw new IllegalStateException(
                    "FFprobe reported unexpected audio codec: " + probeData.codecName() + ", expected mp3"
            );
        }
        if (probeData.sampleRateHz() != CANONICAL_SAMPLE_RATE_HZ) {
            throw new IllegalStateException(
                    "FFprobe reported unexpected sample rate: " + probeData.sampleRateHz()
                            + " Hz, expected " + CANONICAL_SAMPLE_RATE_HZ + " Hz"
            );
        }
        if (probeData.channels() != CANONICAL_CHANNELS) {
            throw new IllegalStateException(
                    "FFprobe reported unexpected channel count: " + probeData.channels()
                            + ", expected " + CANONICAL_CHANNELS
            );
        }
        if (probeData.packetCount() <= 0) {
            throw new IllegalStateException(
                    "FFprobe reported invalid packet count: " + probeData.packetCount() + ", expected > 0"
            );
        }
    }

    private static Duration requirePositiveTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be > 0");
        }
        try {
            timeout.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("timeout is too large", e);
        }
        return timeout;
    }

    private static String diagnosticSuffix(String diagnostics) {
        String trimmed = diagnostics == null ? "" : diagnostics.trim();
        return trimmed.isEmpty() ? "" : ": " + trimmed;
    }

    private static String safeMessage(IOException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? "process I/O failure"
                : failure.getMessage();
    }

    static final class JvmEncoderProcessRunner implements EncoderProcessRunner {

        private static final int COPY_BUFFER_SIZE = 8192;
        private static final int MAX_DIAGNOSTIC_BYTES = 4096;
        private static final long TERMINATION_GRACE_MILLIS = 1000L;
        private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

        @FunctionalInterface
        interface ProcessStarter {
            Process start(List<String> command) throws IOException;
        }

        private final ProcessStarter processStarter;

        JvmEncoderProcessRunner() {
            this(command -> new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start());
        }

        JvmEncoderProcessRunner(ProcessStarter processStarter) {
            this.processStarter = Objects.requireNonNull(processStarter, "processStarter must not be null");
        }

        @Override
        public EncoderProcessResult execute(List<String> command, InputStream source, Duration timeout)
                throws IOException, InterruptedException, TimeoutException {
            Objects.requireNonNull(command, "command must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Duration validatedTimeout = requirePositiveTimeout(timeout);

            Process process;
            try {
                process = processStarter.start(List.copyOf(command));
            } catch (IOException e) {
                throw new IOException("Failed to start FFmpeg encoder process.", e);
            }

            BoundedDiagnostics diagnostics = new BoundedDiagnostics(MAX_DIAGNOSTIC_BYTES);
            ExecutorService pumps = Executors.newFixedThreadPool(2, daemonThreadFactory("novel-ffmpeg-segment-pump-"));
            Future<IOException> sourcePump = pumps.submit(() -> pumpSource(source, process.getOutputStream()));
            Future<IOException> diagnosticPump = pumps.submit(() -> drainDiagnostics(process.getInputStream(), diagnostics));
            long startedNanos = System.nanoTime();
            long timeoutNanos = validatedTimeout.toNanos();

            try {
                boolean finished = process.waitFor(timeoutNanos, TimeUnit.NANOSECONDS);
                if (!finished) {
                    throw new TimeoutException("FFmpeg encoder process exceeded its timeout.");
                }

                int exitCode = process.exitValue();
                IOException sourceFailure = awaitPump(sourcePump, remainingNanos(startedNanos, timeoutNanos));
                IOException diagnosticFailure = awaitPump(diagnosticPump, remainingNanos(startedNanos, timeoutNanos));

                if (exitCode == 0) {
                    throwPumpFailureIfPresent(sourceFailure, diagnosticFailure);
                }
                return new EncoderProcessResult(exitCode, diagnostics.text());
            } catch (InterruptedException e) {
                terminatePreservingPrimary(process, e);
                throw e;
            } catch (TimeoutException | IOException | RuntimeException e) {
                terminatePreservingPrimary(process, e);
                throw e;
            } finally {
                closeProcessStreams(process);
                sourcePump.cancel(true);
                diagnosticPump.cancel(true);
                pumps.shutdownNow();
            }
        }

        private static IOException pumpSource(InputStream source, OutputStream processInput) {
            try (OutputStream stdin = processInput) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    if (read > 0) {
                        stdin.write(buffer, 0, read);
                    }
                }
                stdin.flush();
                return null;
            } catch (IOException e) {
                return new IOException("Failed to stream WAV source to FFmpeg.", e);
            }
        }

        private static IOException drainDiagnostics(InputStream processOutput, BoundedDiagnostics diagnostics) {
            try (InputStream output = processOutput) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = output.read(buffer)) >= 0) {
                    if (read > 0) {
                        diagnostics.append(buffer, 0, read);
                    }
                }
                return null;
            } catch (IOException e) {
                return new IOException("Failed to drain FFmpeg diagnostic output.", e);
            }
        }

        private static IOException awaitPump(Future<IOException> pump, long remainingNanos)
                throws InterruptedException, TimeoutException, IOException {
            if (remainingNanos <= 0) {
                throw new TimeoutException("FFmpeg encoder process exceeded its timeout while draining process streams.");
            }
            try {
                return pump.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                throw new IOException("Unexpected FFmpeg stream pump failure.", cause);
            }
        }

        private static void throwPumpFailureIfPresent(IOException sourceFailure, IOException diagnosticFailure)
                throws IOException {
            if (sourceFailure != null) {
                if (diagnosticFailure != null) {
                    sourceFailure.addSuppressed(diagnosticFailure);
                }
                throw sourceFailure;
            }
            if (diagnosticFailure != null) {
                throw diagnosticFailure;
            }
        }

        private static long remainingNanos(long startedNanos, long timeoutNanos) {
            return timeoutNanos - (System.nanoTime() - startedNanos);
        }

        private static void terminatePreservingPrimary(Process process, Throwable primaryFailure) {
            try {
                terminate(process);
            } catch (InterruptedException terminationInterrupted) {
                primaryFailure.addSuppressed(terminationInterrupted);
                forceTerminateAfterCleanupInterruption(process, primaryFailure);
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException terminationFailure) {
                primaryFailure.addSuppressed(terminationFailure);
            }
        }

        private static void terminate(Process process) throws IOException, InterruptedException {
            try {
                if (!process.isAlive()) {
                    return;
                }

                process.destroy();
                if (process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    return;
                }

                process.destroyForcibly();
                if (!process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    throw new IOException("FFmpeg encoder process remained alive after forced termination.");
                }
            } finally {
                closeProcessStreams(process);
            }
        }

        private static void forceTerminateAfterCleanupInterruption(Process process, Throwable primaryFailure) {
            try {
                process.destroyForcibly();
                if (!process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    primaryFailure.addSuppressed(
                            new IOException("FFmpeg encoder process remained alive after forced termination.")
                    );
                }
            } catch (InterruptedException repeatedInterruption) {
                primaryFailure.addSuppressed(repeatedInterruption);
            } catch (RuntimeException terminationFailure) {
                primaryFailure.addSuppressed(terminationFailure);
            } finally {
                closeProcessStreams(process);
            }
        }

        private static void closeProcessStreams(Process process) {
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
        }

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }

        private static ThreadFactory daemonThreadFactory(String prefix) {
            return runnable -> {
                Thread thread = new Thread(
                        runnable,
                        prefix + THREAD_SEQUENCE.incrementAndGet()
                );
                thread.setDaemon(true);
                return thread;
            };
        }

        private static final class BoundedDiagnostics {

            private final int maxBytes;
            private final ByteArrayOutputStream captured;
            private boolean truncated;

            private BoundedDiagnostics(int maxBytes) {
                this.maxBytes = maxBytes;
                this.captured = new ByteArrayOutputStream(maxBytes);
            }

            private synchronized void append(byte[] bytes, int offset, int length) {
                int remaining = maxBytes - captured.size();
                int accepted = Math.min(remaining, length);
                if (accepted > 0) {
                    captured.write(bytes, offset, accepted);
                }
                if (accepted < length) {
                    truncated = true;
                }
            }

            private synchronized String text() {
                String value = captured.toString(StandardCharsets.UTF_8).trim();
                if (truncated) {
                    return value + "... (truncated)";
                }
                return value;
            }
        }
    }

    static final class JvmSegmentAudioProbeRunner implements SegmentAudioProbeRunner {

        private final String ffprobeExecutable;
        private final ProbeProcessRunner processRunner;

        JvmSegmentAudioProbeRunner(String ffprobeExecutable) {
            this(ffprobeExecutable, new JvmProbeProcessRunner());
        }

        JvmSegmentAudioProbeRunner(String ffprobeExecutable, ProbeProcessRunner processRunner) {
            if (ffprobeExecutable == null || ffprobeExecutable.isBlank()) {
                throw new IllegalArgumentException("ffprobeExecutable must not be blank");
            }
            this.ffprobeExecutable = ffprobeExecutable.trim();
            this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
        }

        @Override
        public SegmentProbeData probe(Path audioFile, Duration timeout)
                throws IOException, InterruptedException, TimeoutException {
            Objects.requireNonNull(audioFile, "audioFile must not be null");
            Duration validatedTimeout = requirePositiveTimeout(timeout);

            List<String> command = List.of(
                    ffprobeExecutable,
                    "-hide_banner",
                    "-v", "error",
                    "-select_streams", "a:0",
                    "-count_packets",
                    "-show_entries", "stream=codec_name,sample_rate,channels,nb_read_packets",
                    "-of", "json",
                    audioFile.toAbsolutePath().toString()
            );

            ProbeProcessResult result = processRunner.execute(command, validatedTimeout);
            if (result.exitCode() != 0) {
                throw new IOException("FFprobe inspection failed with exit code " + result.exitCode()
                        + (result.output().isBlank() ? "" : ": " + result.output()));
            }

            JsonNode root;
            try {
                root = OBJECT_MAPPER.readTree(result.output());
            } catch (Exception e) {
                throw new IOException("FFprobe output is not valid JSON: " + result.output(), e);
            }

            JsonNode streamsNode = root.path("streams");
            if (!streamsNode.isArray() || streamsNode.isEmpty()) {
                throw new IOException("FFprobe output contains no audio streams.");
            }

            JsonNode streamNode = streamsNode.path(0);
            String codecName = streamNode.path("codec_name").asText("");

            JsonNode sampleRateNode = streamNode.path("sample_rate");
            if (sampleRateNode.isMissingNode() || sampleRateNode.isNull()) {
                throw new IOException("FFprobe output is missing sample_rate.");
            }
            int sampleRateHz = sampleRateNode.asInt(-1);

            JsonNode channelsNode = streamNode.path("channels");
            if (channelsNode.isMissingNode() || channelsNode.isNull()) {
                throw new IOException("FFprobe output is missing channels.");
            }
            int channels = channelsNode.asInt(-1);

            JsonNode nbReadPacketsNode = streamNode.path("nb_read_packets");
            if (nbReadPacketsNode.isMissingNode() || nbReadPacketsNode.isNull()) {
                throw new IOException("FFprobe output is missing nb_read_packets.");
            }

            String nbReadPacketsText = nbReadPacketsNode.asText().trim();
            int packetCount;
            try {
                packetCount = Integer.parseInt(nbReadPacketsText);
            } catch (NumberFormatException e) {
                throw new IOException("FFprobe reported non-numeric nb_read_packets: '" + nbReadPacketsText + "'", e);
            }

            if (packetCount <= 0) {
                throw new IOException("FFprobe reported invalid packet count: " + packetCount + ", expected > 0");
            }

            return new SegmentProbeData(codecName, sampleRateHz, channels, packetCount);
        }
    }

    static final class JvmProbeProcessRunner implements ProbeProcessRunner {

        private static final int COPY_BUFFER_SIZE = 8192;
        private static final int MAX_OUTPUT_BYTES = 64 * 1024;
        private static final long TERMINATION_GRACE_MILLIS = 1000L;
        private static final AtomicInteger PROBE_THREAD_SEQUENCE = new AtomicInteger();

        @FunctionalInterface
        interface ProcessStarter {
            Process start(List<String> command) throws IOException;
        }

        private final ProcessStarter processStarter;

        JvmProbeProcessRunner() {
            this(command -> new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start());
        }

        JvmProbeProcessRunner(ProcessStarter processStarter) {
            this.processStarter = Objects.requireNonNull(processStarter, "processStarter must not be null");
        }

        @Override
        public ProbeProcessResult execute(List<String> command, Duration timeout)
                throws IOException, InterruptedException, TimeoutException {
            Objects.requireNonNull(command, "command must not be null");
            Duration validatedTimeout = requirePositiveTimeout(timeout);

            Process process;
            try {
                process = processStarter.start(List.copyOf(command));
            } catch (IOException e) {
                throw new IOException("Failed to start FFprobe process.", e);
            }

            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            AtomicReference<IOException> readerError = new AtomicReference<>();
            ExecutorService pump = Executors.newSingleThreadExecutor(daemonThreadFactory("novel-ffprobe-pump-"));
            Future<?> readerFuture = pump.submit(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        if (read > 0 && captured.size() < MAX_OUTPUT_BYTES) {
                            int toWrite = Math.min(read, MAX_OUTPUT_BYTES - captured.size());
                            captured.write(buffer, 0, toWrite);
                        }
                    }
                } catch (IOException e) {
                    readerError.set(new IOException("Failed to read FFprobe process output.", e));
                }
            });

            long startedNanos = System.nanoTime();
            long timeoutNanos = validatedTimeout.toNanos();

            try {
                boolean finished = process.waitFor(timeoutNanos, TimeUnit.NANOSECONDS);
                if (!finished) {
                    throw new TimeoutException("FFprobe process exceeded its timeout of " + validatedTimeout.toMillis() + " ms.");
                }

                long remainingNanos = timeoutNanos - (System.nanoTime() - startedNanos);
                if (remainingNanos <= 0 && !readerFuture.isDone()) {
                    throw new TimeoutException("FFprobe process timed out while draining output.");
                }

                try {
                    if (remainingNanos <= 0) {
                        readerFuture.get();
                    } else {
                        readerFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
                    }
                } catch (TimeoutException te) {
                    throw new TimeoutException("FFprobe process timed out while draining output.");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException ioFailure) {
                        throw ioFailure;
                    }
                    throw new IOException("Unexpected failure draining FFprobe output.", cause);
                }

                if (readerError.get() != null) {
                    throw readerError.get();
                }

                int exitCode = process.exitValue();
                String output = captured.toString(StandardCharsets.UTF_8).trim();
                return new ProbeProcessResult(exitCode, output);
            } catch (InterruptedException e) {
                terminatePreservingPrimary(process, e);
                throw e;
            } catch (TimeoutException | IOException | RuntimeException e) {
                terminatePreservingPrimary(process, e);
                throw e;
            } finally {
                closeProcessStreams(process);
                readerFuture.cancel(true);
                pump.shutdownNow();
            }
        }

        private static void terminatePreservingPrimary(Process process, Throwable primaryFailure) {
            try {
                terminate(process);
            } catch (InterruptedException terminationInterrupted) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(terminationInterrupted);
                }
                forceTerminateAfterCleanupInterruption(process, primaryFailure);
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException terminationFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(terminationFailure);
                }
            }
        }

        private static void terminate(Process process) throws IOException, InterruptedException {
            try {
                if (!process.isAlive()) {
                    return;
                }
                process.destroy();
                if (process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    return;
                }
                process.destroyForcibly();
                if (!process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    throw new IOException("FFprobe process remained alive after forced termination.");
                }
            } finally {
                closeProcessStreams(process);
            }
        }

        private static void forceTerminateAfterCleanupInterruption(Process process, Throwable primaryFailure) {
            try {
                process.destroyForcibly();
                if (!process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(
                                new IOException("FFprobe process remained alive after forced termination.")
                        );
                    }
                }
            } catch (InterruptedException repeatedInterruption) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(repeatedInterruption);
                }
            } catch (RuntimeException terminationFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(terminationFailure);
                }
            } finally {
                closeProcessStreams(process);
            }
        }

        private static void closeProcessStreams(Process process) {
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
        }

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }

        private static ThreadFactory daemonThreadFactory(String prefix) {
            return runnable -> {
                Thread thread = new Thread(runnable, prefix + PROBE_THREAD_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
        }
    }
}
