package com.universe.media.application.storage;

import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;

import java.time.Duration;

/**
 * Command defining parameters for discovering and sweeping abandoned multipart upload sessions (MS-08E2-L3-2).
 *
 * @param batchSize    maximum number of candidates to process in a single sweep execution (1..100)
 * @param maxRetryCount maximum allowed prior cleanup attempts for retryable candidates
 * @param retryBackoff duration to wait since last cleanup attempt before retrying (or null for no delay)
 */
public record SweepAbandonedMultipartUploadsCommand(
        int batchSize,
        int maxRetryCount,
        Duration retryBackoff
) {
    public static final int DEFAULT_BATCH_SIZE = 50;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofHours(1);

    public SweepAbandonedMultipartUploadsCommand {
        if (batchSize <= 0 || batchSize > MediaUploadSessionRepositoryPort.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    String.format("batchSize must be between 1 and %d, given: %d",
                            MediaUploadSessionRepositoryPort.MAX_BATCH_SIZE, batchSize)
            );
        }
        if (maxRetryCount < 0) {
            throw new IllegalArgumentException("maxRetryCount cannot be negative, given: " + maxRetryCount);
        }
    }

    public SweepAbandonedMultipartUploadsCommand(int batchSize) {
        this(batchSize, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_BACKOFF);
    }

    public SweepAbandonedMultipartUploadsCommand() {
        this(DEFAULT_BATCH_SIZE, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_BACKOFF);
    }
}
