package com.universe.wiki.infrastructure.maintenance;

import com.universe.wiki.application.article.cover.backfill.WikiCoverLegacyTagBackfillResult;
import com.universe.wiki.application.article.cover.backfill.WikiCoverLegacyTagBackfillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Scheduled background maintenance adapter for Wiki cover legacy client-tag backfill (MS-05G8C6).
 *
 * <p>Execution is controlled via Spring property {@code wiki.cover-orphan.backfill.enabled}.
 * Default cron schedule runs at 01:00 AM daily in timezone {@code Asia/Ho_Chi_Minh},
 * prior to orphan discovery (02:00 AM) and orphan reconciliation (03:00 AM).
 */
@Component
@ConditionalOnProperty(
        name = "wiki.cover-orphan.backfill.enabled",
        havingValue = "true"
)
public class WikiCoverLegacyTagBackfillScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WikiCoverLegacyTagBackfillScheduler.class);

    private final WikiCoverLegacyTagBackfillService backfillService;

    public WikiCoverLegacyTagBackfillScheduler(WikiCoverLegacyTagBackfillService backfillService) {
        this.backfillService = Objects.requireNonNull(backfillService, "WikiCoverLegacyTagBackfillService cannot be null.");
    }

    @Scheduled(
            cron = "${wiki.cover-orphan.backfill.cron:0 0 1 * * *}",
            zone = "${wiki.cover-orphan.backfill.zone:Asia/Ho_Chi_Minh}"
    )
    public void runBackfill() {
        LOGGER.info("Starting scheduled Wiki cover legacy client-tag backfill...");
        try {
            WikiCoverLegacyTagBackfillResult result = backfillService.backfillLegacyCoverTags();
            LOGGER.info(
                    "Completed scheduled Wiki cover legacy client-tag backfill. Scanned: {}, Successful/AlreadyTagged: {}, Conflicts: {}, Missing: {}, Failed: {}",
                    result.scannedAssets(),
                    result.successfulAssignmentsOrAlreadyTagged(),
                    result.conflicts(),
                    result.missingMediaAssets(),
                    result.failedAssets()
            );
        } catch (Exception exception) {
            LOGGER.error("Scheduled Wiki cover legacy client-tag backfill encountered an unexpected batch failure.", exception);
        }
    }
}
