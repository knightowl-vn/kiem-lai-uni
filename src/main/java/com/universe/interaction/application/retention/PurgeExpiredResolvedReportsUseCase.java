package com.universe.interaction.application.retention;

import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Application use case for purging expired resolved interaction reports after the 30-day retention window.
 *
 * <p>Execution is strictly bounded to one batch per invocation:
 * computes {@code cutoff = clockPort.now() - 30 days} and delegates to the repository.
 * Terminal reports resolved strictly before the cutoff ({@code resolved_at < cutoff}) are eligible;
 * reports with {@code resolved_at == cutoff} or {@code status == PENDING} are never eligible.
 */
@Service
public class PurgeExpiredResolvedReportsUseCase {

    /**
     * Locked application retention period: 30 days.
     */
    public static final Duration RETENTION_PERIOD = Duration.ofDays(30);

    private final ClockPort clockPort;
    private final InteractionReportRepositoryPort repository;

    public PurgeExpiredResolvedReportsUseCase(
            ClockPort clockPort,
            InteractionReportRepositoryPort repository
    ) {
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.repository = Objects.requireNonNull(repository, "InteractionReportRepositoryPort cannot be null.");
    }

    /**
     * Executes a single bounded batch purge of expired resolved reports.
     *
     * @param batchSize maximum number of expired reports to delete in this execution
     * @return the actual number of deleted report rows
     */
    public int execute(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be greater than 0, given: " + batchSize);
        }

        Instant now = clockPort.now();
        Instant cutoff = now.minus(RETENTION_PERIOD);

        return repository.purgeExpiredResolvedBefore(cutoff, batchSize);
    }
}
