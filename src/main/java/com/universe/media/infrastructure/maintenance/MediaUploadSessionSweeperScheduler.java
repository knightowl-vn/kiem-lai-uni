package com.universe.media.infrastructure.maintenance;

import com.universe.media.application.storage.SweepAbandonedMultipartUploadsCommand;
import com.universe.media.application.storage.SweepAbandonedMultipartUploadsResult;
import com.universe.media.application.storage.SweepAbandonedMultipartUploadsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * Scheduled background sweeper for periodic provider-side cleanup of abandoned multipart upload sessions (MS-08E2-L3-2).
 *
 * <p>Execution is controlled via Spring property {@code media.upload-session-cleanup.schedule.enabled}.
 * Disabled by default ({@code false}).
 *
 * <p><strong>Transaction Boundary:</strong> Non-transactional orchestrator.
 * Delegates entirely to {@link SweepAbandonedMultipartUploadsUseCase}.
 */
@Component
@ConditionalOnProperty(
        name = "media.upload-session-cleanup.schedule.enabled",
        havingValue = "true"
)
public class MediaUploadSessionSweeperScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaUploadSessionSweeperScheduler.class);

    private final SweepAbandonedMultipartUploadsUseCase sweeperUseCase;
    private final int batchSize;
    private final int maxRetries;
    private final Duration retryBackoff;

    public MediaUploadSessionSweeperScheduler(
            SweepAbandonedMultipartUploadsUseCase sweeperUseCase,
            @Value("${media.upload-session-cleanup.schedule.batch-size:50}") int batchSize,
            @Value("${media.upload-session-cleanup.max-retries:3}") int maxRetries,
            @Value("${media.upload-session-cleanup.retry-backoff-ms:3600000}") long retryBackoffMs
    ) {
        this.sweeperUseCase = Objects.requireNonNull(
                sweeperUseCase,
                "SweepAbandonedMultipartUploadsUseCase cannot be null."
        );
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "Batch size must be greater than 0, given: " + batchSize
            );
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException(
                    "Max retries cannot be negative, given: " + maxRetries
            );
        }
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException(
                    "Retry backoff ms cannot be negative, given: " + retryBackoffMs
            );
        }
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.retryBackoff = Duration.ofMillis(retryBackoffMs);
    }

    @Scheduled(
            fixedDelayString = "${media.upload-session-cleanup.schedule.fixed-delay:3600000}"
    )
    public void sweepAbandonedMultipartUploads() {
        LOGGER.info("Starting scheduled sweeper for abandoned multipart upload sessions (batchSize: {}, maxRetries: {})...",
                batchSize, maxRetries);

        try {
            SweepAbandonedMultipartUploadsResult result = sweeperUseCase.execute(
                    new SweepAbandonedMultipartUploadsCommand(batchSize, maxRetries, retryBackoff)
            );

            LOGGER.info(
                    "Completed scheduled sweeper for abandoned multipart upload sessions. Scanned: {}, Attempted: {}, Skipped: {}, Quarantined: {}, VerifiedCleaned: {}, Failed: {}, ExpirationFailed: {}",
                    result.scannedCount(),
                    result.attemptedCount(),
                    result.skippedCount(),
                    result.quarantinedCount(),
                    result.verifiedCleanedCount(),
                    result.failedCount(),
                    result.expirationFailedCount()
            );
        } catch (Exception exception) {
            LOGGER.error(
                    "Scheduled sweeper for abandoned multipart upload sessions encountered an unexpected batch failure [category: {}].",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
