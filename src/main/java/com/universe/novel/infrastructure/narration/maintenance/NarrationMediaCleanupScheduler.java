package com.universe.novel.infrastructure.narration.maintenance;

import com.universe.novel.application.narration.ProcessPendingNarrationMediaCleanupResult;
import com.universe.novel.application.narration.ProcessPendingNarrationMediaCleanupUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * Scheduled background worker for periodic processing of pending narration media cleanup tasks (MS-04.9H.8D1C).
 * <p>
 * Execution is controlled via Spring property {@code novel.narration.media-cleanup.schedule.enabled}.
 * By default, this scheduler is disabled ({@code false}).
 * <p>
 * <strong>Transaction Boundary:</strong> Non-transactional. Delegates entirely to {@link ProcessPendingNarrationMediaCleanupUseCase}.
 */
@Component
@ConditionalOnProperty(
        name = "novel.narration.media-cleanup.schedule.enabled",
        havingValue = "true"
)
public class NarrationMediaCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(NarrationMediaCleanupScheduler.class);

    private final ProcessPendingNarrationMediaCleanupUseCase cleanupUseCase;
    private final int batchSize;

    @Autowired
    public NarrationMediaCleanupScheduler(
            ProcessPendingNarrationMediaCleanupUseCase cleanupUseCase,
            @Value("${novel.narration.media-cleanup.schedule.batch-size:50}") int batchSize,
            @Value("${novel.narration.media-cleanup.schedule.fixed-delay:3600000}") long fixedDelayMs
    ) {
        this.cleanupUseCase = Objects.requireNonNull(
                cleanupUseCase,
                "ProcessPendingNarrationMediaCleanupUseCase must not be null"
        );
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "Batch size must be greater than 0, given: " + batchSize
            );
        }
        if (fixedDelayMs <= 0) {
            throw new IllegalArgumentException(
                    "Scheduling fixed delay must be positive, given: " + fixedDelayMs
            );
        }
        this.batchSize = batchSize;
    }

    public NarrationMediaCleanupScheduler(
            ProcessPendingNarrationMediaCleanupUseCase cleanupUseCase,
            int batchSize
    ) {
        this(cleanupUseCase, batchSize, 3_600_000L);
    }

    public NarrationMediaCleanupScheduler(
            ProcessPendingNarrationMediaCleanupUseCase cleanupUseCase
    ) {
        this(cleanupUseCase, 50, 3_600_000L);
    }

    @Scheduled(
            fixedDelayString = "${novel.narration.media-cleanup.schedule.fixed-delay:3600000}"
    )
    public void cleanupPendingNarrationMedia() {
        log.info("Starting scheduled Narration Media cleanup (batchSize: {})...", batchSize);

        try {
            ProcessPendingNarrationMediaCleanupResult result = cleanupUseCase.execute(batchSize);

            log.info(
                    "Completed scheduled Narration Media cleanup. Candidates: {}, Cleaned: {}, Failed: {}",
                    result.candidates(),
                    result.cleaned(),
                    result.failed()
            );
        } catch (Exception ex) {
            log.error(
                    "Scheduled Narration Media cleanup encountered an unexpected batch failure.",
                    ex
            );
        }
    }
}
