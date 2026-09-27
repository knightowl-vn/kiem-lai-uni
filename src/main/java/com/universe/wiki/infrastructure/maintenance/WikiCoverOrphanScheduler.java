package com.universe.wiki.infrastructure.maintenance;

import com.universe.wiki.application.orphan.WikiCoverOrphanDiscoveryResult;
import com.universe.wiki.application.orphan.WikiCoverOrphanDiscoveryService;
import com.universe.wiki.application.orphan.WikiCoverOrphanReconciliationResult;
import com.universe.wiki.application.orphan.WikiCoverOrphanReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Scheduled background maintenance adapter for Wiki cover orphan discovery and reconciliation (MS-05G8C5).
 *
 * <p>Execution is controlled via Spring property {@code wiki.cover-orphan.schedule.enabled}.
 * Default cron schedules run in timezone {@code Asia/Ho_Chi_Minh}:
 * <ul>
 *     <li>Discovery: 02:00 AM daily (prior to reconciliation)</li>
 *     <li>Reconciliation: 03:00 AM daily</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(
        name = "wiki.cover-orphan.schedule.enabled",
        havingValue = "true"
)
public class WikiCoverOrphanScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WikiCoverOrphanScheduler.class);

    private final WikiCoverOrphanDiscoveryService discoveryService;
    private final WikiCoverOrphanReconciliationService reconciliationService;

    public WikiCoverOrphanScheduler(
            WikiCoverOrphanDiscoveryService discoveryService,
            WikiCoverOrphanReconciliationService reconciliationService
    ) {
        this.discoveryService = Objects.requireNonNull(discoveryService, "WikiCoverOrphanDiscoveryService cannot be null.");
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "WikiCoverOrphanReconciliationService cannot be null.");
    }

    @Scheduled(
            cron = "${wiki.cover-orphan.schedule.discovery-cron:0 0 2 * * *}",
            zone = "${wiki.cover-orphan.schedule.zone:Asia/Ho_Chi_Minh}"
    )
    public void runDiscovery() {
        LOGGER.info("Starting scheduled Wiki cover orphan discovery...");
        try {
            WikiCoverOrphanDiscoveryResult result = discoveryService.discoverOrphans();
            LOGGER.info(
                    "Completed scheduled Wiki cover orphan discovery. Scanned: {}, Observed: {}, Referenced: {}, Failed: {}",
                    result.scannedCandidates(),
                    result.observedOrphans(),
                    result.activeReferenced(),
                    result.failedInspections()
            );
        } catch (Exception exception) {
            LOGGER.error("Scheduled Wiki cover orphan discovery encountered an unexpected batch failure.", exception);
        }
    }

    @Scheduled(
            cron = "${wiki.cover-orphan.schedule.reconciliation-cron:0 0 3 * * *}",
            zone = "${wiki.cover-orphan.schedule.zone:Asia/Ho_Chi_Minh}"
    )
    public void runReconciliation() {
        LOGGER.info("Starting scheduled Wiki cover orphan reconciliation...");
        try {
            WikiCoverOrphanReconciliationResult result = reconciliationService.reconcileOrphans();
            LOGGER.info(
                    "Completed scheduled Wiki cover orphan reconciliation. RecoveredLeases: {}, Claimed: {}, Deleted: {}, ReReferenced: {}, Failed: {}",
                    result.recoveredStaleLeases(),
                    result.eligibleClaimed(),
                    result.successfullyDeleted(),
                    result.activeReReferenced(),
                    result.failedAttempts()
            );
        } catch (Exception exception) {
            LOGGER.error("Scheduled Wiki cover orphan reconciliation encountered an unexpected batch failure.", exception);
        }
    }
}
