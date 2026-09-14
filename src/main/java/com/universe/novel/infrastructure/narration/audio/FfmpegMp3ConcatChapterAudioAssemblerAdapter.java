package com.universe.novel.infrastructure.narration.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.universe.novel.application.narration.ChapterAudioAssemblyCue;
import com.universe.novel.application.narration.ChapterAudioAssemblyRequest;
import com.universe.novel.application.narration.ChapterAudioAssemblyResource;
import com.universe.novel.application.narration.ChapterAudioAssemblyResult;
import com.universe.novel.application.narration.ChapterAudioSegmentSource;
import com.universe.novel.application.ports.ChapterAudioAssemblerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FFmpeg concat-based assembler for canonical 48kHz mono 96kbps MP3 narration segments.
 * <p>
 * Concatenates canonical MP3 segments without re-encoding using {@code -c:a copy},
 * derives intermediate cue intervals directly from per-segment contribution samples,
 * and validates the final chapter artifact with FFprobe.
 */
@Component
public class FfmpegMp3ConcatChapterAudioAssemblerAdapter implements ChapterAudioAssemblerPort {

    static final String OUTPUT_MIME_TYPE = "audio/mpeg";
    static final String CANONICAL_MIME_TYPE = "audio/mpeg";
    static final int CANONICAL_SAMPLE_RATE_HZ = 48_000;
    static final int CANONICAL_CHANNELS = 1;
    static final int SAMPLES_PER_MILLIS = 48;
    static final String CONCAT_MANIFEST_FILENAME = "concat.txt";
    static final String CHAPTER_OUTPUT_FILENAME = "chapter.mp3";
    private static final int COPY_BUFFER_SIZE = 8192;
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000L);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @FunctionalInterface
    interface TempWorkspaceFactory {
        Path create() throws IOException;
    }

    @FunctionalInterface
    interface TempWorkspaceDeleter {
        void delete(Path directory) throws IOException;
    }

    @FunctionalInterface
    interface ConcatProcessRunner {
        ConcatProcessResult execute(List<String> command, Path workingDirectory, Duration timeout)
                throws IOException, InterruptedException, TimeoutException;
    }

    record ConcatProcessResult(int exitCode, String diagnostics) {
        ConcatProcessResult {
            diagnostics = diagnostics == null ? "" : diagnostics;
        }
    }

    record ChapterProbeData(
            String codecName,
            int sampleRateHz,
            int channels,
            BigDecimal durationSeconds
    ) {}

    @FunctionalInterface
    interface ChapterAudioProbeRunner {
        ChapterProbeData probe(Path audioFile, Duration timeout)
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
    private final TempWorkspaceFactory workspaceFactory;
    private final TempWorkspaceDeleter workspaceDeleter;
    private final ConcatProcessRunner processRunner;
    private final ChapterAudioProbeRunner probeRunner;

    public FfmpegMp3ConcatChapterAudioAssemblerAdapter() {
        this("ffmpeg", "ffprobe", Duration.ofSeconds(120));
    }

    @Autowired
    public FfmpegMp3ConcatChapterAudioAssemblerAdapter(
            @Value("${novel.narration.audio.concat-assembler.ffmpeg.path:${novel.narration.audio.encoder.ffmpeg.path:ffmpeg}}") String ffmpegExecutable,
            @Value("${novel.narration.audio.concat-assembler.ffprobe.path:${novel.narration.audio.segment-encoder.ffprobe.path:ffprobe}}") String ffprobeExecutable,
            @Value("${novel.narration.audio.concat-assembler.timeout:120s}") Duration timeout
    ) {
        this(
                ffmpegExecutable,
                ffprobeExecutable,
                timeout,
                () -> Files.createTempDirectory("novel_chapter_concat_"),
                FfmpegMp3ConcatChapterAudioAssemblerAdapter::deleteRecursively,
                new JvmConcatProcessRunner(),
                new JvmChapterAudioProbeRunner(ffprobeExecutable)
        );
    }

    FfmpegMp3ConcatChapterAudioAssemblerAdapter(
            String ffmpegExecutable,
            String ffprobeExecutable,
            Duration timeout,
            TempWorkspaceFactory workspaceFactory,
            TempWorkspaceDeleter workspaceDeleter,
            ConcatProcessRunner processRunner,
            ChapterAudioProbeRunner probeRunner
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
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory must not be null");
        this.workspaceDeleter = Objects.requireNonNull(workspaceDeleter, "workspaceDeleter must not be null");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
        this.probeRunner = Objects.requireNonNull(probeRunner, "probeRunner must not be null");
    }

    @Override
    public ChapterAudioAssemblyResult assemble(ChapterAudioAssemblyRequest request) {
        validateRequest(request);

        Path workspaceDir;
        try {
            workspaceDir = Objects.requireNonNull(workspaceFactory.create(), "workspaceFactory returned null");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create chapter audio temporary workspace.", e);
        }

        try {
            List<ChapterAudioSegmentSource> segments = request.segments();
            materializeSegmentsAndManifest(workspaceDir, segments);

            List<String> concatCommand = concatCommand(ffmpegExecutable);
            ConcatProcessResult processResult = processRunner.execute(concatCommand, workspaceDir, timeout);
            if (processResult.exitCode() != 0) {
                throw new IllegalStateException(
                        "FFmpeg chapter audio concatenation failed with exit code " + processResult.exitCode()
                                + diagnosticSuffix(processResult.diagnostics())
                );
            }

            Path chapterFile = workspaceDir.resolve(CHAPTER_OUTPUT_FILENAME);
            if (!Files.isRegularFile(chapterFile)) {
                throw new IllegalStateException("FFmpeg did not produce a chapter audio file.");
            }
            long sizeBytes = Files.size(chapterFile);
            if (sizeBytes <= 0) {
                throw new IllegalStateException("FFmpeg produced an empty chapter audio file.");
            }

            ChapterProbeData probeData;
            try {
                probeData = probeRunner.probe(chapterFile, timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("FFprobe chapter audio inspection was interrupted.", e);
            } catch (TimeoutException e) {
                throw new IllegalStateException("FFprobe chapter audio inspection timed out after " + timeout.toMillis() + " ms.", e);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to probe concatenated chapter audio with FFprobe: " + safeMessage(e), e);
            }

            validateProbeData(probeData);

            long probedDurationMillis = durationSecondsToMillis(probeData.durationSeconds());
            List<ChapterAudioAssemblyCue> cues = computeCues(segments, probedDurationMillis);

            deleteIntermediateFiles(workspaceDir, segments.size());

            WorkspaceChapterAudioAssemblyResource resource = new WorkspaceChapterAudioAssemblyResource(
                    workspaceDir,
                    chapterFile,
                    OUTPUT_MIME_TYPE,
                    sizeBytes,
                    workspaceDeleter
            );

            return new ChapterAudioAssemblyResult(
                    resource,
                    probedDurationMillis,
                    cues
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IllegalStateException failure = new IllegalStateException("FFmpeg chapter audio concatenation was interrupted.", e);
            deleteWorkspaceQuietlyPreservingFailure(workspaceDir, failure);
            throw failure;
        } catch (TimeoutException e) {
            IllegalStateException failure = new IllegalStateException(
                    "FFmpeg chapter audio concatenation timed out after " + timeout.toMillis() + " ms.",
                    e
            );
            deleteWorkspaceQuietlyPreservingFailure(workspaceDir, failure);
            throw failure;
        } catch (IOException e) {
            IllegalStateException failure = new IllegalStateException(
                    "Failed to concatenate chapter audio with FFmpeg: " + safeMessage(e),
                    e
            );
            deleteWorkspaceQuietlyPreservingFailure(workspaceDir, failure);
            throw failure;
        } catch (RuntimeException e) {
            deleteWorkspaceQuietlyPreservingFailure(workspaceDir, e);
            throw e;
        }
    }

    private static void validateRequest(ChapterAudioAssemblyRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<ChapterAudioSegmentSource> segments = request.segments();
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("Chapter audio assembly request must not be empty.");
        }
        Set<UUID> seenIds = new HashSet<>();
        Set<Integer> seenIndexes = new HashSet<>();
        int previousIndex = -1;
        for (ChapterAudioSegmentSource segment : segments) {
            if (segment == null) {
                throw new IllegalArgumentException("Assembly request segments must not contain null elements.");
            }
            if (!seenIds.add(segment.segmentId())) {
                throw new IllegalArgumentException("Duplicate segmentId in assembly request: " + segment.segmentId());
            }
            if (!seenIndexes.add(segment.segmentIndex())) {
                throw new IllegalArgumentException("Duplicate segmentIndex in assembly request: " + segment.segmentIndex());
            }
            if (segment.segmentIndex() <= previousIndex) {
                throw new IllegalArgumentException("segmentIndex values must be strictly increasing in input order");
            }
            previousIndex = segment.segmentIndex();
            requireCanonicalMime(segment);
            requireCanonicalTiming(segment);
        }
    }

    private static void requireCanonicalMime(ChapterAudioSegmentSource source) {
        String mimeType = source.mimeType();
        String normalized = mimeType == null ? "" : mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!CANONICAL_MIME_TYPE.equals(normalized)) {
            throw new IllegalArgumentException(
                    "Unsupported segment audio MIME type: " + mimeType + " for segment: " + source.segmentId()
                            + ", expected " + CANONICAL_MIME_TYPE
            );
        }
    }

    private static void requireCanonicalTiming(ChapterAudioSegmentSource source) {
        Long samples = source.encodedContributionSamples();
        Integer rate = source.encodedSampleRateHz();
        if (samples == null || rate == null) {
            throw new IllegalArgumentException(
                    "Segment audio must have encoded timing metadata: " + source.segmentId()
            );
        }
        if (samples <= 0) {
            throw new IllegalArgumentException(
                    "Segment audio encodedContributionSamples must be > 0: " + samples + " for segment: " + source.segmentId()
            );
        }
        if (rate != CANONICAL_SAMPLE_RATE_HZ) {
            throw new IllegalArgumentException(
                    "Segment audio sample rate must be " + CANONICAL_SAMPLE_RATE_HZ + " Hz, but found " + rate
                            + " Hz for segment: " + source.segmentId()
            );
        }
        if (samples % SAMPLES_PER_MILLIS != 0) {
            throw new IllegalArgumentException(
                    "Segment audio encodedContributionSamples (" + samples + ") must be an exact multiple of "
                            + SAMPLES_PER_MILLIS + " samples/ms for segment: " + source.segmentId()
            );
        }
    }

    private static void materializeSegmentsAndManifest(
            Path workspaceDir,
            List<ChapterAudioSegmentSource> segments
    ) throws IOException {
        StringBuilder concatManifest = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            ChapterAudioSegmentSource source = segments.get(i);
            String segmentFilename = String.format(Locale.ROOT, "segment-%06d.mp3", i);
            Path segmentFile = workspaceDir.resolve(segmentFilename);

            try (InputStream in = source.binarySource().openStream()) {
                if (in == null) {
                    throw new IllegalArgumentException("Segment binary source returned null stream: " + source.segmentId());
                }
                long bytesWritten = 0L;
                try (OutputStream out = Files.newOutputStream(segmentFile)) {
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        if (read > 0) {
                            out.write(buffer, 0, read);
                            bytesWritten += read;
                        }
                    }
                }
                if (bytesWritten <= 0L) {
                    throw new IllegalArgumentException("Segment binary source produced an empty file: " + source.segmentId());
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read segment audio source: " + source.segmentId(), e);
            }

            concatManifest.append("file '").append(segmentFilename).append("'\n");
        }

        Path concatFile = workspaceDir.resolve(CONCAT_MANIFEST_FILENAME);
        Files.writeString(concatFile, concatManifest.toString(), StandardCharsets.UTF_8);
    }

    static List<String> concatCommand(String ffmpegExecutable) {
        return List.of(
                ffmpegExecutable,
                "-hide_banner",
                "-loglevel", "error",
                "-nostats",
                "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", CONCAT_MANIFEST_FILENAME,
                "-map_metadata", "-1",
                "-map_chapters", "-1",
                "-id3v2_version", "0",
                "-vn",
                "-c:a", "copy",
                "-f", "mp3",
                CHAPTER_OUTPUT_FILENAME
        );
    }

    static List<String> probeCommand(String ffprobeExecutable, Path audioFile) {
        return List.of(
                ffprobeExecutable,
                "-hide_banner",
                "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "stream=codec_name,sample_rate,channels,duration:format=duration",
                "-of", "json",
                audioFile.toAbsolutePath().toString()
        );
    }

    private static void validateProbeData(ChapterProbeData probeData) {
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
        if (probeData.durationSeconds() == null || probeData.durationSeconds().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException(
                    "FFprobe reported non-positive duration: " + probeData.durationSeconds()
            );
        }
    }

    static long durationSecondsToMillis(BigDecimal durationSeconds) {
        Objects.requireNonNull(durationSeconds, "durationSeconds must not be null");
        if (durationSeconds.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("durationSeconds must be > 0: " + durationSeconds);
        }
        // Explicit deterministic policy: convert seconds to milliseconds and round with HALF_UP to whole milliseconds.
        return durationSeconds.multiply(THOUSAND).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static List<ChapterAudioAssemblyCue> computeCues(
            List<ChapterAudioSegmentSource> segments,
            long probedDurationMillis
    ) {
        int segmentCount = segments.size();
        long[] startMillis = new long[segmentCount];
        long cumulativeSamples = 0L;

        for (int i = 0; i < segmentCount; i++) {
            ChapterAudioSegmentSource source = segments.get(i);
            long startSample = cumulativeSamples;
            startMillis[i] = startSample / SAMPLES_PER_MILLIS;
            cumulativeSamples = Math.addExact(cumulativeSamples, source.encodedContributionSamples());
        }

        long lastStartMillis = startMillis[segmentCount - 1];
        if (probedDurationMillis <= lastStartMillis) {
            throw new IllegalStateException(
                    "Probed final chapter duration (" + probedDurationMillis
                            + " ms) must be greater than last cue start (" + lastStartMillis + " ms)."
            );
        }

        List<ChapterAudioAssemblyCue> cues = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            ChapterAudioSegmentSource source = segments.get(i);
            long cueStart = startMillis[i];
            long cueEnd = (i < segmentCount - 1) ? startMillis[i + 1] : probedDurationMillis;

            if (cueEnd <= cueStart) {
                throw new IllegalStateException(
                        "Invalid cue interval [" + cueStart + ", " + cueEnd + "] for segment: " + source.segmentId()
                );
            }

            cues.add(new ChapterAudioAssemblyCue(
                    i,
                    source.segmentId(),
                    source.segmentIndex(),
                    cueStart,
                    cueEnd
            ));
        }

        return List.copyOf(cues);
    }

    private static void deleteIntermediateFiles(Path workspaceDir, int segmentCount) {
        try {
            Files.deleteIfExists(workspaceDir.resolve(CONCAT_MANIFEST_FILENAME));
            for (int i = 0; i < segmentCount; i++) {
                String filename = String.format(Locale.ROOT, "segment-%06d.mp3", i);
                Files.deleteIfExists(workspaceDir.resolve(filename));
            }
        } catch (IOException ignored) {
            // Intermediate cleanup is best-effort optimization; full cleanup occurs on resource close.
        }
    }

    private void deleteWorkspaceQuietlyPreservingFailure(Path workspaceDir, Throwable primaryFailure) {
        if (workspaceDir == null) {
            return;
        }
        try {
            workspaceDeleter.delete(workspaceDir);
        } catch (IOException | RuntimeException cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            var paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
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

    static final class WorkspaceChapterAudioAssemblyResource implements ChapterAudioAssemblyResource {

        private final Path workspaceDir;
        private final Path chapterFile;
        private final String mimeType;
        private final long sizeBytes;
        private final TempWorkspaceDeleter workspaceDeleter;
        private final Object lock = new Object();
        private int activeStreams = 0;
        private boolean closed = false;

        WorkspaceChapterAudioAssemblyResource(
                Path workspaceDir,
                Path chapterFile,
                String mimeType,
                long sizeBytes,
                TempWorkspaceDeleter workspaceDeleter
        ) {
            this.workspaceDir = Objects.requireNonNull(workspaceDir, "workspaceDir must not be null");
            this.chapterFile = Objects.requireNonNull(chapterFile, "chapterFile must not be null");
            this.mimeType = Objects.requireNonNull(mimeType, "mimeType must not be null");
            this.sizeBytes = sizeBytes;
            this.workspaceDeleter = Objects.requireNonNull(workspaceDeleter, "workspaceDeleter must not be null");
        }

        @Override
        public String mimeType() {
            return mimeType;
        }

        @Override
        public long sizeBytes() {
            return sizeBytes;
        }

        @Override
        public InputStream openStream() {
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("ChapterAudioAssemblyResource has already been closed.");
                }
                try {
                    InputStream rawStream = Files.newInputStream(chapterFile);
                    activeStreams++;
                    return new TrackedInputStream(rawStream);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to open assembled chapter audio file.", e);
                }
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                if (activeStreams > 0) {
                    throw new IllegalStateException(
                            "Cannot close resource: " + activeStreams + " open stream(s) returned by openStream() must be closed first."
                    );
                }
                try {
                    workspaceDeleter.delete(workspaceDir);
                    closed = true;
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete chapter audio temporary workspace: " + workspaceDir, e);
                }
            }
        }

        private final class TrackedInputStream extends FilterInputStream {

            private boolean streamClosed = false;

            private TrackedInputStream(InputStream in) {
                super(in);
            }

            @Override
            public void close() throws IOException {
                synchronized (this) {
                    if (streamClosed) {
                        return;
                    }
                    streamClosed = true;
                    try {
                        in.close();
                    } finally {
                        synchronized (lock) {
                            activeStreams--;
                        }
                    }
                }
            }
        }
    }

    static final class JvmConcatProcessRunner implements ConcatProcessRunner {

        private static final int COPY_BUFFER_SIZE = 8192;
        private static final int MAX_DIAGNOSTIC_BYTES = 4096;
        private static final long TERMINATION_GRACE_MILLIS = 1000L;
        private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

        @FunctionalInterface
        interface ProcessStarter {
            Process start(List<String> command, Path workingDirectory) throws IOException;
        }

        private final ProcessStarter processStarter;

        JvmConcatProcessRunner() {
            this((command, workingDir) -> new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start());
        }

        JvmConcatProcessRunner(ProcessStarter processStarter) {
            this.processStarter = Objects.requireNonNull(processStarter, "processStarter must not be null");
        }

        @Override
        public ConcatProcessResult execute(List<String> command, Path workingDirectory, Duration timeout)
                throws IOException, InterruptedException, TimeoutException {
            Objects.requireNonNull(command, "command must not be null");
            Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
            Duration validatedTimeout = requirePositiveTimeout(timeout);

            Process process;
            try {
                process = processStarter.start(List.copyOf(command), workingDirectory);
            } catch (IOException e) {
                throw new IOException("Failed to start FFmpeg concat process.", e);
            }

            // FFmpeg does not read from stdin when using file concat script
            closeQuietly(process.getOutputStream());

            BoundedDiagnostics diagnostics = new BoundedDiagnostics(MAX_DIAGNOSTIC_BYTES);
            ExecutorService pump = Executors.newSingleThreadExecutor(daemonThreadFactory("novel-ffmpeg-concat-pump-"));
            Future<IOException> diagnosticPump = pump.submit(() -> drainDiagnostics(process.getInputStream(), diagnostics));
            long startedNanos = System.nanoTime();
            long timeoutNanos = validatedTimeout.toNanos();

            try {
                boolean finished = process.waitFor(timeoutNanos, TimeUnit.NANOSECONDS);
                if (!finished) {
                    throw new TimeoutException("FFmpeg concat process exceeded its timeout of " + validatedTimeout.toMillis() + " ms.");
                }

                int exitCode = process.exitValue();
                long remainingNanos = timeoutNanos - (System.nanoTime() - startedNanos);
                IOException diagnosticFailure = awaitPump(diagnosticPump, remainingNanos);

                if (exitCode == 0 && diagnosticFailure != null) {
                    throw diagnosticFailure;
                }
                return new ConcatProcessResult(exitCode, diagnostics.text());
            } catch (InterruptedException e) {
                terminatePreservingPrimary(process, e);
                throw e;
            } catch (TimeoutException | IOException | RuntimeException e) {
                terminatePreservingPrimary(process, e);
                throw e;
            } finally {
                closeProcessStreams(process);
                diagnosticPump.cancel(true);
                pump.shutdownNow();
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
                return new IOException("Failed to drain FFmpeg concat diagnostic output.", e);
            }
        }

        private static IOException awaitPump(Future<IOException> pump, long remainingNanos)
                throws InterruptedException, TimeoutException, IOException {
            if (remainingNanos <= 0) {
                throw new TimeoutException("FFmpeg concat process exceeded its timeout while draining diagnostic stream.");
            }
            try {
                return pump.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                throw new IOException("Unexpected FFmpeg stream pump failure.", cause);
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
                    throw new IOException("FFmpeg concat process remained alive after forced termination.");
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
                            new IOException("FFmpeg concat process remained alive after forced termination.")
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

    static final class JvmChapterAudioProbeRunner implements ChapterAudioProbeRunner {

        private final String ffprobeExecutable;
        private final ProbeProcessRunner processRunner;

        JvmChapterAudioProbeRunner(String ffprobeExecutable) {
            this(ffprobeExecutable, new JvmProbeProcessRunner());
        }

        JvmChapterAudioProbeRunner(String ffprobeExecutable, ProbeProcessRunner processRunner) {
            if (ffprobeExecutable == null || ffprobeExecutable.isBlank()) {
                throw new IllegalArgumentException("ffprobeExecutable must not be blank");
            }
            this.ffprobeExecutable = ffprobeExecutable.trim();
            this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
        }

        @Override
        public ChapterProbeData probe(Path audioFile, Duration timeout)
                throws IOException, InterruptedException, TimeoutException {
            Objects.requireNonNull(audioFile, "audioFile must not be null");
            Duration validatedTimeout = requirePositiveTimeout(timeout);

            List<String> command = probeCommand(ffprobeExecutable, audioFile);
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
            int sampleRateHz = streamNode.path("sample_rate").asInt(-1);
            int channels = streamNode.path("channels").asInt(-1);

            String durationText = streamNode.path("duration").asText("");
            if (durationText.isBlank() || "N/A".equalsIgnoreCase(durationText)) {
                durationText = root.path("format").path("duration").asText("");
            }
            if (durationText.isBlank() || "N/A".equalsIgnoreCase(durationText)) {
                throw new IOException("FFprobe output contains no stream or format duration.");
            }

            BigDecimal durationSeconds;
            try {
                durationSeconds = new BigDecimal(durationText.trim());
            } catch (NumberFormatException e) {
                throw new IOException("FFprobe reported non-numeric duration: '" + durationText + "'", e);
            }

            return new ChapterProbeData(codecName, sampleRateHz, channels, durationSeconds);
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
            java.util.concurrent.atomic.AtomicReference<IOException> readerError = new java.util.concurrent.atomic.AtomicReference<>();
            ExecutorService pump = Executors.newSingleThreadExecutor(daemonThreadFactory("novel-ffprobe-concat-pump-"));
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
                    primaryFailure.addSuppressed(
                            new IOException("FFprobe process remained alive after forced termination.")
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
                        prefix + PROBE_THREAD_SEQUENCE.incrementAndGet()
                );
                thread.setDaemon(true);
                return thread;
            };
        }
    }
}
