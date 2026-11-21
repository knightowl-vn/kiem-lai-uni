package com.universe.media.infrastructure.hls;

import com.universe.media.application.asset.VideoUploadValidator;
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
import java.util.Objects;

/**
 * Validated configuration for the dormant Media HLS source MP4 chunked downloader (MS-08 HLS2B3A1).
 *
 * <p>Disabled by default alongside {@link HlsPackagingProperties} ({@code media.hls.packaging.enabled=false})
 * so normal web-app startup and CI test runs initialize zero source-download infrastructure.
 */
@Component
@ConditionalOnProperty(prefix = "media.hls.packaging", name = "enabled", havingValue = "true")
public class HlsSourceDownloadProperties {

    public static final long DEFAULT_CHUNK_SIZE_BYTES = 8L * 1024L * 1024L; // 8 MiB
    public static final long MIN_CHUNK_SIZE_BYTES = 4096L; // 4 KiB
    public static final long MAX_CHUNK_SIZE_BYTES = 64L * 1024L * 1024L; // 64 MiB
    public static final long DEFAULT_MAX_SOURCE_BYTES = VideoUploadValidator.MAX_MULTIPART_VIDEO_SIZE_BYTES; // 50 GiB
    public static final int DEFAULT_MAX_CHUNK_RETRIES = 3;
    public static final int MAX_ALLOWED_CHUNK_RETRIES = 10;
    public static final long DEFAULT_RETRY_BACKOFF_MILLIS = 250L;
    public static final long MAX_ALLOWED_RETRY_BACKOFF_MILLIS = 60_000L;
    public static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofMinutes(30);
    public static final long DEFAULT_MIN_FREE_DISK_BYTES = 512L * 1024L * 1024L; // 512 MiB

    private final Path tempRootDir;
    private final long chunkSizeBytes;
    private final long maxSourceBytes;
    private final int maxChunkRetries;
    private final long retryBackoffMillis;
    private final Duration totalTimeout;
    private final long minFreeDiskBytes;

    @Autowired
    public HlsSourceDownloadProperties(
            @Value("${media.hls.source-download.temp-root-dir:}") String tempRootDir,
            @Value("${media.hls.source-download.chunk-size-bytes:8388608}") long chunkSizeBytes,
            @Value("${media.hls.source-download.max-source-bytes:53687091200}") long maxSourceBytes,
            @Value("${media.hls.source-download.max-chunk-retries:3}") int maxChunkRetries,
            @Value("${media.hls.source-download.retry-backoff-ms:250}") long retryBackoffMillis,
            @Value("${media.hls.source-download.total-timeout:30m}") String totalTimeout,
            @Value("${media.hls.source-download.min-free-disk-bytes:536870912}") long minFreeDiskBytes,
            @Autowired(required = false) HlsPackagingProperties packagingProperties
    ) {
        this(
                resolveTempRoot(tempRootDir),
                chunkSizeBytes,
                maxSourceBytes,
                maxChunkRetries,
                retryBackoffMillis,
                parseTotalTimeout(totalTimeout),
                minFreeDiskBytes,
                packagingProperties != null ? packagingProperties.scratchRootDir() : null
        );
    }

    public HlsSourceDownloadProperties(
            Path tempRootDir,
            long chunkSizeBytes,
            long maxSourceBytes,
            int maxChunkRetries,
            long retryBackoffMillis,
            Duration totalTimeout,
            long minFreeDiskBytes
    ) {
        this(
                tempRootDir,
                chunkSizeBytes,
                maxSourceBytes,
                maxChunkRetries,
                retryBackoffMillis,
                totalTimeout,
                minFreeDiskBytes,
                null
        );
    }

    public HlsSourceDownloadProperties(
            Path tempRootDir,
            long chunkSizeBytes,
            long maxSourceBytes,
            int maxChunkRetries,
            long retryBackoffMillis,
            Duration totalTimeout,
            long minFreeDiskBytes,
            Path packagingScratchRootDir
    ) {
        if (tempRootDir == null) {
            throw new IllegalArgumentException("media.hls.source-download.temp-root-dir cannot be null.");
        }
        Path normalizedTempRoot = tempRootDir.toAbsolutePath().normalize();
        HlsPackagingProperties.validateDirectoryOutsideWorktree(
                normalizedTempRoot,
                "HLS source download tempRootDir"
        );
        if (packagingScratchRootDir != null) {
            validateDisjointFromPackagingScratch(normalizedTempRoot, packagingScratchRootDir);
        }
        this.tempRootDir = normalizedTempRoot;

        if (chunkSizeBytes < MIN_CHUNK_SIZE_BYTES || chunkSizeBytes > MAX_CHUNK_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "media.hls.source-download.chunk-size-bytes must be in "
                            + MIN_CHUNK_SIZE_BYTES + ".." + MAX_CHUNK_SIZE_BYTES + ", got: " + chunkSizeBytes
            );
        }
        this.chunkSizeBytes = chunkSizeBytes;

        if (maxSourceBytes <= 0L || maxSourceBytes > VideoUploadValidator.MAX_MULTIPART_VIDEO_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "media.hls.source-download.max-source-bytes must be in 1.."
                            + VideoUploadValidator.MAX_MULTIPART_VIDEO_SIZE_BYTES + ", got: " + maxSourceBytes
            );
        }
        this.maxSourceBytes = maxSourceBytes;

        if (maxChunkRetries < 0 || maxChunkRetries > MAX_ALLOWED_CHUNK_RETRIES) {
            throw new IllegalArgumentException(
                    "media.hls.source-download.max-chunk-retries must be in 0.."
                            + MAX_ALLOWED_CHUNK_RETRIES + ", got: " + maxChunkRetries
            );
        }
        this.maxChunkRetries = maxChunkRetries;

        if (retryBackoffMillis < 0L || retryBackoffMillis > MAX_ALLOWED_RETRY_BACKOFF_MILLIS) {
            throw new IllegalArgumentException(
                    "media.hls.source-download.retry-backoff-ms must be in 0.."
                            + MAX_ALLOWED_RETRY_BACKOFF_MILLIS + ", got: " + retryBackoffMillis
            );
        }
        this.retryBackoffMillis = retryBackoffMillis;

        if (totalTimeout == null || totalTimeout.isNegative() || totalTimeout.isZero()) {
            throw new IllegalArgumentException("media.hls.source-download.total-timeout must be strictly positive.");
        }
        this.totalTimeout = totalTimeout;

        if (minFreeDiskBytes < 0L) {
            throw new IllegalArgumentException(
                    "media.hls.source-download.min-free-disk-bytes must be >= 0, got: " + minFreeDiskBytes
            );
        }
        this.minFreeDiskBytes = minFreeDiskBytes;
    }

    public static HlsSourceDownloadProperties defaults(Path tempRootDir) {
        return new HlsSourceDownloadProperties(
                tempRootDir,
                DEFAULT_CHUNK_SIZE_BYTES,
                DEFAULT_MAX_SOURCE_BYTES,
                DEFAULT_MAX_CHUNK_RETRIES,
                DEFAULT_RETRY_BACKOFF_MILLIS,
                DEFAULT_TOTAL_TIMEOUT,
                DEFAULT_MIN_FREE_DISK_BYTES,
                null
        );
    }

    static void validateDisjointFromPackagingScratch(Path sourceTempRoot, Path packagingScratchRoot) {
        Objects.requireNonNull(sourceTempRoot, "sourceTempRoot cannot be null.");
        Objects.requireNonNull(packagingScratchRoot, "packagingScratchRoot cannot be null.");

        Path normSource = sourceTempRoot.toAbsolutePath().normalize();
        Path normScratch = packagingScratchRoot.toAbsolutePath().normalize();
        if (pathsOverlap(normSource, normScratch)) {
            throw new IllegalArgumentException(
                    "HLS source download tempRootDir [" + normSource
                            + "] must be strictly separate from HLS packaging scratchRootDir [" + normScratch + "]."
            );
        }

        Path realSource = resolveEffectiveRealPath(normSource);
        Path realScratch = resolveEffectiveRealPath(normScratch);
        if (pathsOverlap(realSource, realScratch)) {
            throw new IllegalArgumentException(
                    "Resolved HLS source download tempRootDir [" + realSource
                            + "] must not overlap HLS packaging scratchRootDir [" + realScratch + "]."
            );
        }
    }

    private static boolean pathsOverlap(Path first, Path second) {
        return first.equals(second) || first.startsWith(second) || second.startsWith(first);
    }

    private static Path resolveEffectiveRealPath(Path normalized) {
        Path cursor = normalized;
        while (cursor != null) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Path realAncestor = cursor.toRealPath();
                    return cursor.equals(normalized)
                            ? realAncestor
                            : realAncestor.resolve(cursor.relativize(normalized)).normalize();
                } catch (IOException ignored) {
                    return normalized;
                }
            }
            cursor = cursor.getParent();
        }
        return normalized;
    }

    private static Path resolveTempRoot(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "kiemlai-hls-sources");
    }

    private static Duration parseTotalTimeout(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("media.hls.source-download.total-timeout must not be blank.");
        }
        return DurationStyle.detectAndParse(raw.trim());
    }

    public Path tempRootDir() {
        return tempRootDir;
    }

    public long chunkSizeBytes() {
        return chunkSizeBytes;
    }

    public long maxSourceBytes() {
        return maxSourceBytes;
    }

    public int maxChunkRetries() {
        return maxChunkRetries;
    }

    public long retryBackoffMillis() {
        return retryBackoffMillis;
    }

    public Duration totalTimeout() {
        return totalTimeout;
    }

    public long minFreeDiskBytes() {
        return minFreeDiskBytes;
    }
}
