package com.universe.media.infrastructure.hls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Validated configuration for the dormant Media HLS local packaging engine (MS-08 HLS2B1).
 *
 * <p>Disabled by default ({@code media.hls.packaging.enabled=false}) so normal application startup
 * and CI test runs never require local {@code ffmpeg}/{@code ffprobe} binaries.
 */
@Component
@ConditionalOnProperty(prefix = "media.hls.packaging", name = "enabled", havingValue = "true")
public class HlsPackagingProperties {

    public static final int DEFAULT_MAX_CONCURRENT_ENCODES = 1;
    public static final int DEFAULT_FFMPEG_THREADS = 4;
    public static final int MIN_FFMPEG_THREADS = 1;
    public static final int MAX_FFMPEG_THREADS = 32;
    public static final Duration DEFAULT_ENCODE_TIMEOUT = Duration.ofMinutes(60);
    public static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(60);
    public static final long DEFAULT_MIN_FREE_SCRATCH_BYTES = 512L * 1024L * 1024L;
    public static final int DEFAULT_SEGMENT_DURATION_SECONDS = 6;
    public static final String DEFAULT_X264_PRESET = "veryfast";

    private static final Set<String> ALLOWED_X264_PRESETS = Set.of(
            "ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow"
    );

    private final boolean enabled;
    private final String ffmpegExecutable;
    private final String ffprobeExecutable;
    private final int maxConcurrentEncodes;
    private final int ffmpegThreads;
    private final Duration encodeTimeout;
    private final Duration probeTimeout;
    private final Path scratchRootDir;
    private final long minFreeScratchBytes;
    private final String x264Preset;
    private final int defaultSegmentDurationSeconds;

    @Autowired
    public HlsPackagingProperties(
            @Value("${media.hls.packaging.enabled:false}") boolean enabled,
            @Value("${media.hls.packaging.ffmpeg-path:ffmpeg}") String ffmpegExecutable,
            @Value("${media.hls.packaging.ffprobe-path:ffprobe}") String ffprobeExecutable,
            @Value("${media.hls.packaging.max-concurrent-encodes:1}") int maxConcurrentEncodes,
            @Value("${media.hls.packaging.ffmpeg-threads:4}") int ffmpegThreads,
            @Value("${media.hls.packaging.encode-timeout:60m}") String encodeTimeout,
            @Value("${media.hls.packaging.probe-timeout:60s}") String probeTimeout,
            @Value("${media.hls.packaging.scratch-root-dir:}") String scratchRootDir,
            @Value("${media.hls.packaging.min-free-scratch-bytes:536870912}") long minFreeScratchBytes,
            @Value("${media.hls.packaging.x264-preset:veryfast}") String x264Preset,
            @Value("${media.hls.packaging.segment-duration-seconds:6}") int defaultSegmentDurationSeconds
    ) {
        this(
                enabled,
                ffmpegExecutable,
                ffprobeExecutable,
                maxConcurrentEncodes,
                ffmpegThreads,
                DurationStyle.detectAndParse(requireNonBlank(encodeTimeout, "media.hls.packaging.encode-timeout")),
                DurationStyle.detectAndParse(requireNonBlank(probeTimeout, "media.hls.packaging.probe-timeout")),
                resolveScratchRoot(scratchRootDir),
                minFreeScratchBytes,
                x264Preset,
                defaultSegmentDurationSeconds
        );
    }

    public HlsPackagingProperties(
            boolean enabled,
            String ffmpegExecutable,
            String ffprobeExecutable,
            int maxConcurrentEncodes,
            int ffmpegThreads,
            Duration encodeTimeout,
            Duration probeTimeout,
            Path scratchRootDir,
            long minFreeScratchBytes,
            String x264Preset,
            int defaultSegmentDurationSeconds
    ) {
        this.enabled = enabled;
        this.ffmpegExecutable = requireNonBlank(ffmpegExecutable, "media.hls.packaging.ffmpeg-path");
        this.ffprobeExecutable = requireNonBlank(ffprobeExecutable, "media.hls.packaging.ffprobe-path");
        if (maxConcurrentEncodes < 1) {
            throw new IllegalArgumentException(
                    "media.hls.packaging.max-concurrent-encodes must be >= 1, got: " + maxConcurrentEncodes
            );
        }
        this.maxConcurrentEncodes = maxConcurrentEncodes;
        if (ffmpegThreads < MIN_FFMPEG_THREADS || ffmpegThreads > MAX_FFMPEG_THREADS) {
            throw new IllegalArgumentException(
                    "media.hls.packaging.ffmpeg-threads must be in 1..32, got: " + ffmpegThreads
            );
        }
        this.ffmpegThreads = ffmpegThreads;
        if (encodeTimeout == null || encodeTimeout.isNegative() || encodeTimeout.isZero()) {
            throw new IllegalArgumentException("media.hls.packaging.encode-timeout must be strictly positive.");
        }
        this.encodeTimeout = encodeTimeout;
        if (probeTimeout == null || probeTimeout.isNegative() || probeTimeout.isZero()) {
            throw new IllegalArgumentException("media.hls.packaging.probe-timeout must be strictly positive.");
        }
        this.probeTimeout = probeTimeout;
        if (scratchRootDir == null) {
            throw new IllegalArgumentException("scratchRootDir cannot be null.");
        }
        Path normalizedScratch = scratchRootDir.toAbsolutePath().normalize();
        validateScratchRootOutsideWorktree(normalizedScratch);
        this.scratchRootDir = normalizedScratch;
        if (minFreeScratchBytes < 0) {
            throw new IllegalArgumentException(
                    "media.hls.packaging.min-free-scratch-bytes must be >= 0, got: " + minFreeScratchBytes
            );
        }
        this.minFreeScratchBytes = minFreeScratchBytes;
        String preset = requireNonBlank(x264Preset, "media.hls.packaging.x264-preset").toLowerCase(Locale.ROOT);
        if (!ALLOWED_X264_PRESETS.contains(preset)) {
            throw new IllegalArgumentException("Unsupported x264 preset: " + x264Preset);
        }
        this.x264Preset = preset;
        if (defaultSegmentDurationSeconds < 2 || defaultSegmentDurationSeconds > 10) {
            throw new IllegalArgumentException(
                    "media.hls.packaging.segment-duration-seconds must be 2..10, got: " + defaultSegmentDurationSeconds
            );
        }
        this.defaultSegmentDurationSeconds = defaultSegmentDurationSeconds;
    }

    public static HlsPackagingProperties localDefaults(Path scratchRootDir) {
        return new HlsPackagingProperties(
                true,
                "ffmpeg",
                "ffprobe",
                DEFAULT_MAX_CONCURRENT_ENCODES,
                DEFAULT_FFMPEG_THREADS,
                DEFAULT_ENCODE_TIMEOUT,
                DEFAULT_PROBE_TIMEOUT,
                scratchRootDir,
                DEFAULT_MIN_FREE_SCRATCH_BYTES,
                DEFAULT_X264_PRESET,
                DEFAULT_SEGMENT_DURATION_SECONDS
        );
    }

    private static Path resolveScratchRoot(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "kiemlai-hls-work");
    }

    static void validateScratchRootOutsideWorktree(Path scratchRootDir) {
        validateDirectoryOutsideWorktree(scratchRootDir, "HLS packaging scratchRootDir");
    }

    static void validateDirectoryOutsideWorktree(Path targetDir, String directoryLabel) {
        Objects.requireNonNull(targetDir, directoryLabel + " cannot be null.");
        List<Path> worktreeRoots = detectWorktreeRoots();
        Path normalized = targetDir.toAbsolutePath().normalize();
        rejectIfInsideAnyWorktree(normalized, normalized, worktreeRoots, directoryLabel);

        Path existingAncestor = findNearestExistingPath(normalized);
        if (existingAncestor != null) {
            final Path realAncestor;
            try {
                realAncestor = existingAncestor.toRealPath();
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Unable to verify real path of " + directoryLabel + " [" + normalized + "]: " + e.getMessage(),
                        e
                );
            }
            Path effectiveReal = existingAncestor.equals(normalized)
                    ? realAncestor
                    : realAncestor.resolve(existingAncestor.relativize(normalized)).normalize();
            rejectIfInsideAnyWorktree(normalized, effectiveReal, worktreeRoots, directoryLabel);
        }
    }

    static List<Path> detectWorktreeRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        collectWorktreeRootsFrom(cwd, roots);
        try {
            Path realCwd = cwd.toRealPath();
            collectWorktreeRootsFrom(realCwd, roots);
        } catch (IOException ignored) {
            // Best-effort real cwd check alongside lexical cwd
        }
        return List.copyOf(roots);
    }

    static boolean isInsideAnyWorktree(Path candidate, List<Path> worktreeRoots) {
        Path normalized = candidate.toAbsolutePath().normalize();
        for (Path root : worktreeRoots) {
            if (normalized.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static void collectWorktreeRootsFrom(Path start, Set<Path> roots) {
        Path cursor = start;
        while (cursor != null) {
            if (Files.exists(cursor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(cursor.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
                roots.add(cursor.toAbsolutePath().normalize());
                try {
                    roots.add(cursor.toRealPath());
                } catch (IOException ignored) {
                    // Best-effort real path registration
                }
            }
            cursor = cursor.getParent();
        }
    }

    private static Path findNearestExistingPath(Path normalized) {
        Path cursor = normalized;
        while (cursor != null) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private static void rejectIfInsideAnyWorktree(
            Path configured,
            Path candidate,
            List<Path> worktreeRoots,
            String directoryLabel
    ) {
        if (isInsideAnyWorktree(candidate, worktreeRoots)) {
            throw new IllegalArgumentException(
                    directoryLabel + " must reside outside repository worktree "
                            + worktreeRoots + ": configured=" + configured + ", resolved=" + candidate
            );
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value.trim();
    }

    public boolean enabled() {
        return enabled;
    }

    public String ffmpegExecutable() {
        return ffmpegExecutable;
    }

    public String ffprobeExecutable() {
        return ffprobeExecutable;
    }

    public int maxConcurrentEncodes() {
        return maxConcurrentEncodes;
    }

    public int ffmpegThreads() {
        return ffmpegThreads;
    }

    public Duration encodeTimeout() {
        return encodeTimeout;
    }

    public Duration probeTimeout() {
        return probeTimeout;
    }

    public Path scratchRootDir() {
        return scratchRootDir;
    }

    public long minFreeScratchBytes() {
        return minFreeScratchBytes;
    }

    public String x264Preset() {
        return x264Preset;
    }

    public int defaultSegmentDurationSeconds() {
        return defaultSegmentDurationSeconds;
    }
}
