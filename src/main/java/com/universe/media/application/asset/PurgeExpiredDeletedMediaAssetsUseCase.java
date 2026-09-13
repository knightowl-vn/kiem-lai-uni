package com.universe.media.application.asset;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.shared.time.ClockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Application use case for discovering and orchestrating the batch purge of expired DELETED {@link MediaAsset} entities.
 *
 * <p><strong>Retention Policy:</strong>
 * Reuses {@link PurgeDeletedMediaAssetUseCase#RETENTION_GRACE_PERIOD} (7 days) without duplicating retention constants.
 *
 * <p><strong>Bounded Batch &amp; Failure Isolation:</strong>
 * Discovers up to {@code command.batchSize()} candidates via {@link MediaAssetRepositoryPort#findExpiredDeleted(Instant, int)}.
 * Each candidate is purged individually through {@link PurgeDeletedMediaAssetUseCase}.
 * A failure on any single candidate is caught, logged, and isolated so subsequent candidates in the batch
 * proceed unaffected. Failed assets remain {@code DELETED} and will be retried in subsequent sweeper executions.
 *
 * <p><strong>Transaction Boundary:</strong>
 * This orchestrator is intentionally <em>NOT</em> {@code @Transactional}. No database transaction is held
 * across candidate iteration or physical binary storage I/O.
 */
@Service
public class PurgeExpiredDeletedMediaAssetsUseCase {

    private static final Logger LOGGER = LoggerFactory.getLogger(PurgeExpiredDeletedMediaAssetsUseCase.class);

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final PurgeDeletedMediaAssetUseCase purgeDeletedMediaAssetUseCase;
    private final ClockPort clockPort;

    public PurgeExpiredDeletedMediaAssetsUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            PurgeDeletedMediaAssetUseCase purgeDeletedMediaAssetUseCase,
            ClockPort clockPort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort,
                "MediaAssetRepositoryPort cannot be null."
        );
        this.purgeDeletedMediaAssetUseCase = Objects.requireNonNull(
                purgeDeletedMediaAssetUseCase,
                "PurgeDeletedMediaAssetUseCase cannot be null."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort cannot be null."
        );
    }

    public PurgeExpiredDeletedMediaAssetsResult execute(PurgeExpiredDeletedMediaAssetsCommand command) {
        Objects.requireNonNull(command, "PurgeExpiredDeletedMediaAssetsCommand cannot be null.");

        int batchSize = command.batchSize();
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "Batch size must be greater than 0, given: " + batchSize
            );
        }

        Instant now = clockPort.now();
        Instant cutoff = now.minus(PurgeDeletedMediaAssetUseCase.RETENTION_GRACE_PERIOD);

        List<MediaAsset> candidates = mediaAssetRepositoryPort.findExpiredDeleted(cutoff, batchSize);

        int candidateCount = candidates.size();
        int purgedCount = 0;
        int failedCount = 0;

        for (MediaAsset candidate : candidates) {
            try {
                purgeDeletedMediaAssetUseCase.execute(new PurgeDeletedMediaAssetCommand(candidate.getId()));
                purgedCount++;
            } catch (Exception exception) {
                failedCount++;
                LOGGER.warn(
                        "Failed to purge expired deleted media asset [ID: {}]. Asset remains DELETED for future retry.",
                        candidate.getId(),
                        exception
                );
            }
        }

        return new PurgeExpiredDeletedMediaAssetsResult(
                candidateCount,
                purgedCount,
                failedCount,
                now
        );
    }
}
