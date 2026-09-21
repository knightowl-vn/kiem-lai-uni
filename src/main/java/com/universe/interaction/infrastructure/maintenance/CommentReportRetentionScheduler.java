package com.universe.interaction.infrastructure.maintenance;

import com.universe.interaction.application.retention.PurgeExpiredResolvedReportsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Scheduled background task for periodic physical cleanup of expired resolved interaction reports (30-day retention).
 *
 * <p>Execution is controlled via property {@code interaction.report-retention.schedule.enabled}.
 * By default, runs at 04:00 AM daily in timezone {@code Asia/Ho_Chi_Minh}, off-peak and non-colliding
 * with Wiki cleanup (03:00) and Media cleanup (03:30).
 */
@Component
@ConditionalOnProperty(
        name = "interaction.report-retention.schedule.enabled",
        havingValue = "true"
)
public class CommentReportRetentionScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommentReportRetentionScheduler.class);

    private final PurgeExpiredResolvedReportsUseCase useCase;
    private final int batchSize;

    public CommentReportRetentionScheduler(
            PurgeExpiredResolvedReportsUseCase useCase,
            @Value("${interaction.report-retention.schedule.batch-size:50}") int batchSize
    ) {
        this.useCase = Objects.requireNonNull(useCase, "PurgeExpiredResolvedReportsUseCase cannot be null.");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be greater than 0, given: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    @Scheduled(
            cron = "${interaction.report-retention.schedule.cron:0 0 4 * * *}",
            zone = "${interaction.report-retention.schedule.zone:Asia/Ho_Chi_Minh}"
    )
    public void runRetentionCleanup() {
        LOGGER.info("Starting scheduled retention purge for expired resolved comment reports (batchSize: {})...", batchSize);

        try {
            int purged = useCase.execute(batchSize);
            LOGGER.info("Completed scheduled retention purge for expired resolved comment reports. Purged: {}", purged);
        } catch (Exception exception) {
            LOGGER.error(
                    "Scheduled retention purge for expired resolved comment reports encountered an unexpected batch failure.",
                    exception
            );
        }
    }
}
