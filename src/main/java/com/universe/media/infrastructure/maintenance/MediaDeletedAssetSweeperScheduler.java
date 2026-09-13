package com.universe.media.infrastructure.maintenance;

import com.universe.media.application.asset.PurgeExpiredDeletedMediaAssetsCommand;
import com.universe.media.application.asset.PurgeExpiredDeletedMediaAssetsResult;
import com.universe.media.application.asset.PurgeExpiredDeletedMediaAssetsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Scheduled background sweeper for periodic cleanup of expired DELETED media assets.
 *
 * <p>Execution is controlled via Spring property {@code media.deleted-asset-cleanup.schedule.enabled}.
 * By default, runs at 03:30 AM daily in timezone {@code Asia/Ho_Chi_Minh} to avoid colliding with Wiki cleanup at 03:00.
 */
@Component
@ConditionalOnProperty(
        name = "media.deleted-asset-cleanup.schedule.enabled",
        havingValue = "true"
)
public class MediaDeletedAssetSweeperScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaDeletedAssetSweeperScheduler.class);

    private final PurgeExpiredDeletedMediaAssetsUseCase sweeperUseCase;
    private final int batchSize;

    public MediaDeletedAssetSweeperScheduler(
            PurgeExpiredDeletedMediaAssetsUseCase sweeperUseCase,
            @Value("${media.deleted-asset-cleanup.schedule.batch-size:50}") int batchSize
    ) {
        this.sweeperUseCase = Objects.requireNonNull(
                sweeperUseCase,
                "PurgeExpiredDeletedMediaAssetsUseCase cannot be null."
        );
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "Batch size must be greater than 0, given: " + batchSize
            );
        }
        this.batchSize = batchSize;
    }

    @Scheduled(
            cron = "${media.deleted-asset-cleanup.schedule.cron:0 30 3 * * *}",
            zone = "${media.deleted-asset-cleanup.schedule.zone:Asia/Ho_Chi_Minh}"
    )
    public void sweepExpiredDeletedAssets() {
        LOGGER.info("Starting scheduled sweeper for expired DELETED media assets (batchSize: {})...", batchSize);

        try {
            PurgeExpiredDeletedMediaAssetsResult result = sweeperUseCase.execute(
                    new PurgeExpiredDeletedMediaAssetsCommand(batchSize)
            );

            LOGGER.info(
                    "Completed scheduled sweeper for expired DELETED media assets. Candidates: {}, Purged: {}, Failed: {}",
                    result.candidates(),
                    result.purged(),
                    result.failed()
            );
        } catch (Exception exception) {
            LOGGER.error(
                    "Scheduled sweeper for expired DELETED media assets encountered an unexpected batch failure.",
                    exception
            );
        }
    }
}
