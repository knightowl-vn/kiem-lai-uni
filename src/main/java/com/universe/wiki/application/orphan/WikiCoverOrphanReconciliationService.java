package com.universe.wiki.application.orphan;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRecord;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult;
import com.universe.wiki.infrastructure.maintenance.WikiCoverOrphanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Application service for reconciling eligible Wiki cover orphan candidates (MS-05G8C5.2).
 *
 * <p>Enforces:
 * <ul>
 *     <li>Phase 1: Retry unassigned or stale DELETING candidates for at-least-once Media deletion</li>
 *     <li>Phase 2: Stale PROCESSING lease recovery back to PENDING (excluding DELETING rows)</li>
 *     <li>Phase 3: Bounded eligible PENDING claiming with atomic DELETING fence transition</li>
 *     <li>Reference-verified, locked DELETING fence before invoking remote Media deletion</li>
 *     <li>Remote Media logical delete executed strictly outside database transactions</li>
 *     <li>Conditional DELETING epoch cleanup on Media delete success or MediaAssetNotFoundException</li>
 *     <li>At-least-once retry preservation in DELETING status upon transient failures</li>
 * </ul>
 */
@Service
public class WikiCoverOrphanReconciliationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WikiCoverOrphanReconciliationService.class);
    private static final int MAX_ERROR_SUMMARY_LENGTH = 500;

    private final WikiCoverOrphanRepositoryPort orphanRepositoryPort;
    private final WikiArticleRepositoryPort articleRepositoryPort;
    private final MediaContract mediaContract;
    private final ClockPort clockPort;
    private final WikiCoverOrphanProperties properties;

    // Optional test hook executed immediately after deletion fence transition and before remote Media delete
    private BiConsumer<UUID, UUID> preDeleteHook = null;

    public WikiCoverOrphanReconciliationService(
            WikiCoverOrphanRepositoryPort orphanRepositoryPort,
            WikiArticleRepositoryPort articleRepositoryPort,
            MediaContract mediaContract,
            ClockPort clockPort,
            WikiCoverOrphanProperties properties
    ) {
        this.orphanRepositoryPort = Objects.requireNonNull(orphanRepositoryPort, "WikiCoverOrphanRepositoryPort cannot be null.");
        this.articleRepositoryPort = Objects.requireNonNull(articleRepositoryPort, "WikiArticleRepositoryPort cannot be null.");
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.properties = Objects.requireNonNull(properties, "WikiCoverOrphanProperties cannot be null.");
    }

    public void setPreDeleteHook(BiConsumer<UUID, UUID> preDeleteHook) {
        this.preDeleteHook = preDeleteHook;
    }

    /**
     * Executes one complete 3-phase reconciliation pass:
     * 1. Retries stale/unassigned DELETING candidates.
     * 2. Recovers stale PROCESSING leases back to PENDING.
     * 3. Claims eligible PENDING candidates, establishes durable DELETING fence, and deletes from Media.
     *
     * @return summary metrics of the reconciliation run
     */
    public WikiCoverOrphanReconciliationResult reconcileOrphans() {
        Instant now = clockPort.now();
        Instant leaseCutoff = now.minus(properties.getProcessingLeaseDuration());
        int batchSize = properties.getReconciliationBatchSize();

        int recoveredLeases = 0;
        int eligibleClaimed = 0;
        int successfullyDeleted = 0;
        int activeReReferenced = 0;
        int failedAttempts = 0;

        // ====================================================================
        // Phase 1: Retry stale or unassigned DELETING candidates
        // ====================================================================
        List<WikiCoverOrphanRecord> staleDeletingCandidates = orphanRepositoryPort.findStaleDeletingCandidates(leaseCutoff, batchSize);
        for (WikiCoverOrphanRecord candidate : staleDeletingCandidates) {
            UUID assetId = candidate.mediaAssetId();
            UUID claimToken = UUID.randomUUID();
            Instant claimTimestamp = clockPort.now();

            boolean claimed = orphanRepositoryPort.claimDeletingForRetry(assetId, leaseCutoff, claimToken, claimTimestamp);
            if (!claimed) {
                continue;
            }

            try {
                mediaContract.delete(assetId);
                orphanRepositoryPort.deleteClaimedDeletingEpoch(assetId, claimToken);
                successfullyDeleted++;
                LOGGER.info("Successfully deleted retry-claimed Media asset [{}] and cleaned DELETING epoch.", assetId);
            } catch (MediaAssetNotFoundException notFound) {
                LOGGER.info("Media asset [{}] was already absent during retry. Cleaning DELETING epoch.", assetId);
                orphanRepositoryPort.deleteClaimedDeletingEpoch(assetId, claimToken);
                successfullyDeleted++;
            } catch (RuntimeException mediaException) {
                failedAttempts++;
                LOGGER.error(
                        "Transient failure during Media delete retry for asset [{}]: {}. Releasing DELETING claim for retry.",
                        assetId,
                        mediaException.getMessage(),
                        mediaException
                );
                String errorSummary = truncateErrorSummary(mediaException.getMessage());
                orphanRepositoryPort.releaseDeletingClaimForRetry(assetId, claimToken, errorSummary, clockPort.now());
            }
        }

        // ====================================================================
        // Phase 2: Stale lease recovery (strictly PROCESSING -> PENDING)
        // ====================================================================
        recoveredLeases = orphanRepositoryPort.recoverStaleProcessing(leaseCutoff, now);
        if (recoveredLeases > 0) {
            LOGGER.info("Recovered {} stale PROCESSING leases back to PENDING.", recoveredLeases);
        }

        // ====================================================================
        // Phase 3: Bounded eligible PENDING candidate retrieval & processing
        // ====================================================================
        Instant graceCutoff = now.minus(properties.getOrphanGrace());
        List<WikiCoverOrphanRecord> eligibleCandidates = orphanRepositoryPort.findEligiblePendingCandidates(graceCutoff, batchSize);
        LOGGER.info(
                "Starting Wiki cover orphan reconciliation. GraceCutoff: {}, BatchSize: {}, EligibleFound: {}",
                graceCutoff,
                batchSize,
                eligibleCandidates.size()
        );

        for (WikiCoverOrphanRecord candidate : eligibleCandidates) {
            UUID assetId = candidate.mediaAssetId();
            UUID claimToken = UUID.randomUUID();
            Instant claimTimestamp = clockPort.now();

            boolean claimed = orphanRepositoryPort.claimIfEligible(assetId, graceCutoff, claimToken, claimTimestamp);
            if (!claimed) {
                LOGGER.debug("Skipping asset [{}] - failed to acquire atomic claim.", assetId);
                continue;
            }
            eligibleClaimed++;

            try {
                // Prepare durable deletion fence under short DB transaction with reference re-check
                PrepareDeletionFenceResult fenceResult = orphanRepositoryPort.prepareDeletionFence(assetId, claimToken, clockPort.now());

                if (fenceResult == PrepareDeletionFenceResult.REFERENCED) {
                    LOGGER.info("Asset [{}] was re-referenced by a Wiki article. Invalidate orphan epoch.", assetId);
                    activeReReferenced++;
                    continue;
                }
                if (fenceResult == PrepareDeletionFenceResult.LOST_OWNERSHIP) {
                    LOGGER.warn("Worker lost claim ownership for asset [{}] before deletion fence. Aborting.", assetId);
                    continue;
                }

                // Testing hook for simulating concurrent events after fence transition
                if (preDeleteHook != null) {
                    preDeleteHook.accept(assetId, claimToken);
                }

                // Destructive Media logical delete outside DB transaction
                try {
                    mediaContract.delete(assetId);
                    orphanRepositoryPort.deleteClaimedDeletingEpoch(assetId, claimToken);
                    successfullyDeleted++;
                    LOGGER.info("Successfully deleted orphaned Media asset [{}] and cleaned DELETING epoch.", assetId);
                } catch (MediaAssetNotFoundException notFound) {
                    LOGGER.info("Media asset [{}] was already absent from Media platform. Cleaning DELETING epoch.", assetId);
                    orphanRepositoryPort.deleteClaimedDeletingEpoch(assetId, claimToken);
                    successfullyDeleted++;
                } catch (RuntimeException mediaException) {
                    failedAttempts++;
                    LOGGER.error(
                            "Transient failure during Media delete for asset [{}]: {}. Releasing DELETING claim for retry.",
                            assetId,
                            mediaException.getMessage(),
                            mediaException
                    );
                    String errorSummary = truncateErrorSummary(mediaException.getMessage());
                    orphanRepositoryPort.releaseDeletingClaimForRetry(assetId, claimToken, errorSummary, clockPort.now());
                }

            } catch (Exception unexpectedException) {
                failedAttempts++;
                LOGGER.error(
                        "Unexpected error while processing claimed asset [{}]: {}. Releasing DELETING claim for retry.",
                        assetId,
                        unexpectedException.getMessage(),
                        unexpectedException
                );
                String errorSummary = truncateErrorSummary(unexpectedException.getMessage());
                orphanRepositoryPort.releaseDeletingClaimForRetry(assetId, claimToken, errorSummary, clockPort.now());
            }
        }

        LOGGER.info(
                "Completed Wiki cover orphan reconciliation. RecoveredLeases: {}, Claimed: {}, Deleted: {}, ReReferenced: {}, Failed: {}",
                recoveredLeases,
                eligibleClaimed,
                successfullyDeleted,
                activeReReferenced,
                failedAttempts
        );

        return new WikiCoverOrphanReconciliationResult(
                recoveredLeases,
                eligibleClaimed,
                successfullyDeleted,
                activeReReferenced,
                failedAttempts
        );
    }

    private String truncateErrorSummary(String message) {
        if (message == null || message.isBlank()) {
            return "Transient error during reconciliation.";
        }
        String trimmed = message.trim();
        if (trimmed.length() <= MAX_ERROR_SUMMARY_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_ERROR_SUMMARY_LENGTH);
    }
}
