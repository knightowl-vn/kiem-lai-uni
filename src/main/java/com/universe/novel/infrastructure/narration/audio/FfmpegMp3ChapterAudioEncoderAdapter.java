package com.universe.novel.infrastructure.narration.audio;

import com.universe.novel.application.narration.ChapterAudioAssemblyResource;
import com.universe.novel.application.narration.ChapterAudioEncodingRequest;
import com.universe.novel.application.narration.ChapterAudioEncodingResult;
import com.universe.novel.application.ports.ChapterAudioEncoderPort;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FFmpeg-backed MP3 encoder for H.9C chapter PCM/WAV assembly resources.
 */
@Component
public class FfmpegMp3ChapterAudioEncoderAdapter implements ChapterAudioEncoderPort {

    static final String OUTPUT_MIME_TYPE = "audio/mpeg";
    private static final String SOURCE_MIME_TYPE = "audio/wav";
    private static final String MP3_CODEC = "libmp3lame";
    private static final String MP3_BITRATE = "96k";

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

    private final String ffmpegExecutable;
    private final Duration timeout;
    private final TempFileFactory tempFileFactory;
    private final TempFileDeleter tempFileDeleter;
    private final EncoderProcessRunner processRunner;

    @Autowired
    public FfmpegMp3ChapterAudioEncoderAdapter(
            @Value("${novel.narration.audio.encoder.ffmpeg.path:ffmpeg}") String ffmpegExecutable,
            @Value("${novel.narration.audio.encoder.ffmpeg.timeout:120s}") Duration timeout
    ) {
        this(
                ffmpegExecutable,
                timeout,
                () -> Files.createTempFile("novel_chapter_audio_", ".mp3"),
                Files::deleteIfExists,
                new JvmEncoderProcessRunner()
        );
    }

    FfmpegMp3ChapterAudioEncoderAdapter(
            String ffmpegExecutable,
            Duration timeout,
            TempFileFactory tempFileFactory,
            TempFileDeleter tempFileDeleter,
            EncoderProcessRunner processRunner
    ) {
        if (ffmpegExecutable == null || ffmpegExecutable.isBlank()) {
            throw new IllegalArgumentException("ffmpegExecutable must not be blank");
        }
        this.ffmpegExecutable = ffmpegExecutable.trim();
        this.timeout = requirePositiveTimeout(timeout);
        this.tempFileFactory = Objects.requireNonNull(tempFileFactory, "tempFileFactory must not be null");
        this.tempFileDeleter = Objects.requireNonNull(tempFileDeleter, "tempFileDeleter must not be null");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
    }

    @Override
    public ChapterAudioEncodingResult encode(ChapterAudioEncodingRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requireWavSource(request.source());

        Path tempFile = createTempFile();
        try {
            EncoderProcessResult processResult;
            try (InputStream sourceStream = openSourceStream(request.source())) {
                processResult = processRunner.execute(commandFor(tempFile), sourceStream, timeout);
            }

            if (processResult.exitCode() != 0) {
                throw new IllegalStateException(
                        "FFmpeg chapter audio encoding failed with exit code " + processResult.exitCode()
                                + diagnosticSuffix(processResult.diagnostics())
                );
            }

            if (!Files.isRegularFile(tempFile)) {
                throw new IllegalStateException("FFmpeg did not produce an encoded chapter audio file.");
            }
            long sizeBytes = Files.size(tempFile);
            if (sizeBytes <= 0) {
                throw new IllegalStateException("FFmpeg produced an empty encoded chapter audio file.");
            }

            return new ChapterAudioEncodingResult(
                    new TempFileChapterAudioEncodedResource(tempFile, OUTPUT_MIME_TYPE, sizeBytes)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IllegalStateException failure = new IllegalStateException("FFmpeg chapter audio encoding was interrupted.", e);
            deleteTempFileAfterFailure(tempFile, failure);
            throw failure;
        } catch (TimeoutException e) {
            IllegalStateException failure = new IllegalStateException(
                    "FFmpeg chapter audio encoding timed out after " + timeout.toMillis() + " ms.",
                    e
            );
            deleteTempFileAfterFailure(tempFile, failure);
            throw failure;
        } catch (IOException e) {
            IllegalStateException failure = new IllegalStateException(
                    "Failed to encode chapter audio with FFmpeg: " + safeMessage(e),
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
                "-vn",
                "-c:a", MP3_CODEC,
                "-b:a", MP3_BITRATE,
                "-f", "mp3",
                outputFile.toAbsolutePath().toString()
        );
    }

    private static void requireWavSource(ChapterAudioAssemblyResource source) {
        String mimeType = source.mimeType();
        String normalized = mimeType == null ? "" : mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!SOURCE_MIME_TYPE.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported chapter audio encoding source MIME type: " + mimeType);
        }
    }

    private static InputStream openSourceStream(ChapterAudioAssemblyResource source) {
        InputStream sourceStream = source.openStream();
        if (sourceStream == null) {
            throw new IllegalStateException("ChapterAudioAssemblyResource returned a null stream.");
        }
        return sourceStream;
    }

    private Path createTempFile() {
        try {
            return Objects.requireNonNull(tempFileFactory.create(), "tempFileFactory returned null");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create encoded chapter audio temporary file.", e);
        }
    }

    private void deleteTempFileAfterFailure(Path tempFile, RuntimeException primaryFailure) {
        try {
            tempFileDeleter.deleteIfExists(tempFile);
        } catch (IOException | RuntimeException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
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
            ExecutorService pumps = Executors.newFixedThreadPool(2, daemonThreadFactory());
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
                // Process termination is already best-effort failure cleanup.
            }
        }

        private static ThreadFactory daemonThreadFactory() {
            return runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "novel-ffmpeg-stream-pump-" + THREAD_SEQUENCE.incrementAndGet()
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
}
