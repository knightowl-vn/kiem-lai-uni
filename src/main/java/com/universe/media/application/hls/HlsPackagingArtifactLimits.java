package com.universe.media.application.hls;

import java.time.Duration;
import java.util.Objects;

/**
 * Explicit bounded artifact limits and retry backoff policy supplied to
 * {@link ProcessClaimedHlsPackagingLeaseUseCase}.
 */
public record HlsPackagingArtifactLimits(
        int maxObjectCount,
        long maxSingleObjectBytes,
        Duration baseRetryBackoff,
        Duration maxRetryBackoff
) {

    public static final int DEFAULT_MAX_OBJECT_COUNT = 5000;
    public static final long DEFAULT_MAX_SINGLE_OBJECT_BYTES = 64L * 1024L * 1024L;
    public static final Duration DEFAULT_BASE_RETRY_BACKOFF = Duration.ofMinutes(1);
    public static final Duration DEFAULT_MAX_RETRY_BACKOFF = Duration.ofMinutes(15);

    public HlsPackagingArtifactLimits {
        if (maxObjectCount < 4) {
            throw new IllegalArgumentException("maxObjectCount must be >= 4, got: " + maxObjectCount);
        }
        if (maxSingleObjectBytes <= 0L) {
            throw new IllegalArgumentException("maxSingleObjectBytes must be > 0, got: " + maxSingleObjectBytes);
        }
        Objects.requireNonNull(baseRetryBackoff, "baseRetryBackoff cannot be null.");
        Objects.requireNonNull(maxRetryBackoff, "maxRetryBackoff cannot be null.");
        if (baseRetryBackoff.isNegative() || baseRetryBackoff.isZero()) {
            throw new IllegalArgumentException("baseRetryBackoff must be strictly positive.");
        }
        if (maxRetryBackoff.compareTo(baseRetryBackoff) < 0) {
            throw new IllegalArgumentException("maxRetryBackoff must be >= baseRetryBackoff.");
        }
    }

    public static HlsPackagingArtifactLimits defaults() {
        return new HlsPackagingArtifactLimits(
                DEFAULT_MAX_OBJECT_COUNT,
                DEFAULT_MAX_SINGLE_OBJECT_BYTES,
                DEFAULT_BASE_RETRY_BACKOFF,
                DEFAULT_MAX_RETRY_BACKOFF
        );
    }
}
