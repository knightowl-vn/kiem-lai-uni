package com.universe.wiki.application.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for Wiki-owned cover orphan reconciliation state and atomic claim epoch primitives.
 */
public interface WikiCoverOrphanRepositoryPort {

    /**
     * Outcome of attempting to transition a PROCESSING orphan candidate to the DELETING fence.
     */
    enum PrepareDeletionFenceResult {
        /**
         * Successfully verified zero references and transitioned row to DELETING status.
         */
        FENCED_FOR_DELETION,

        /**
         * Live article reference detected during fence transaction; orphan epoch invalidated.
         */
        REFERENCED,

        /**
         * Worker lost claim ownership (lease expired, claimed by peer, or row absent).
         */
        LOST_OWNERSHIP
    }

    /**
     * Outcome of attempting to clear an orphan epoch when discovering an active Wiki reference.
     */
    enum ClearReferencedOrphanResult {
        /**
         * The orphan epoch (in PENDING or PROCESSING status) was atomically deleted.
         */
        CLEARED,

        /**
         * No orphan epoch existed for the media asset.
         */
        ABSENT,

        /**
         * An orphan epoch exists in DELETING status; preserved without modification.
         */
        DELETING_PRESERVED
    }

    /**
     * Atomically clears an orphan epoch for an asset observed to have an active Wiki reference,
     * provided the epoch is NOT in DELETING status. If the row is in DELETING status, preserves the
     * durable deletion fence without modification.
     *
     * @param mediaAssetId the media asset ID
     * @return {@link ClearReferencedOrphanResult} outcome
     */
    ClearReferencedOrphanResult clearNonDeletingOrphanForReference(UUID mediaAssetId);

    /**
     * Atomically records an orphan observation if absent. If a row already exists, preserves
     * the existing orphan epoch without resetting first_seen_orphan_at, status, claim_token,
     * locked_at, or retry_count.
     *
     * @param mediaAssetId the unreferenced media asset ID
     * @param observedAt timestamp of observation
     */
    void recordOrphanObservation(UUID mediaAssetId, Instant observedAt);

    /**
     * Invalidates the current orphan epoch when the media asset is authoritatively re-referenced by Wiki.
     *
     * @param mediaAssetId the media asset ID
     * @return true if an existing orphan epoch was removed, false if no row existed
     */
    boolean deleteByMediaAssetId(UUID mediaAssetId);

    /**
     * Coordinates cover attachment under pessimistic locking (Reference-Attach Gate).
     *
     * <p>If an orphan record exists for this asset:
     * <ul>
     *     <li>If {@code status == DELETING}: throws {@code WikiCoverMediaAssetDeletingException}.</li>
     *     <li>If {@code status IN (PENDING, PROCESSING)}: deletes the orphan epoch to cancel reconciliation.</li>
     * </ul>
     *
     * @param mediaAssetId the media asset being attached as a cover
     */
    void coordinateCoverAttachment(UUID mediaAssetId);

    /**
     * Retrieves a bounded batch of eligible pending orphan candidates ordered deterministically.
     *
     * @param graceCutoff upper timestamp cutoff for the grace period (inclusive)
     * @param limit maximum number of candidates to return
     * @return deterministic list of eligible orphan records ordered by (first_seen_orphan_at ASC, media_asset_id ASC)
     */
    List<WikiCoverOrphanRecord> findEligiblePendingCandidates(Instant graceCutoff, int limit);

    /**
     * Atomically claims an eligible pending orphan candidate for worker processing.
     *
     * @param mediaAssetId the media asset ID
     * @param graceCutoff upper timestamp cutoff for the grace period (inclusive)
     * @param claimToken unique worker lease token
     * @param now current mutation timestamp
     * @return true if claim was acquired (1 row transitioned to PROCESSING), false otherwise
     */
    boolean claimIfEligible(UUID mediaAssetId, Instant graceCutoff, UUID claimToken, Instant now);

    /**
     * Atomically transitions a PROCESSING candidate to DELETING status within a short DB transaction,
     * verifying active claim token ownership and re-verifying zero live article references under a lock.
     *
     * @param mediaAssetId the media asset ID
     * @param claimToken the worker claim token
     * @param now current mutation timestamp
     * @return {@link PrepareDeletionFenceResult} outcome
     */
    PrepareDeletionFenceResult prepareDeletionFence(UUID mediaAssetId, UUID claimToken, Instant now);

    /**
     * Verifies whether the specified claim token currently owns the active PROCESSING epoch.
     *
     * @param mediaAssetId the media asset ID
     * @param claimToken the worker claim token
     * @return true if the row exists, status is PROCESSING, and claim_token matches; false otherwise
     */
    boolean isClaimOwned(UUID mediaAssetId, UUID claimToken);

    /**
     * Conditionally deletes the orphan row upon successful reconciliation cleanup, ensuring only
     * the active claim token holder can delete the epoch.
     *
     * @param mediaAssetId the media asset ID
     * @param claimToken the worker claim token
     * @return true if the claimed epoch was deleted, false otherwise
     */
    boolean deleteClaimedEpoch(UUID mediaAssetId, UUID claimToken);

    /**
     * Conditionally deletes a DELETING orphan row upon successful Media deletion cleanup.
     *
     * @param mediaAssetId the media asset ID
     * @param claimToken the worker claim token
     * @return true if the claimed DELETING epoch was deleted, false otherwise
     */
    boolean deleteClaimedDeletingEpoch(UUID mediaAssetId, UUID claimToken);

    /**
     * Releases a claimed epoch back to PENDING following a transient failure, incrementing the
     * retry counter and recording bounded error details while preserving first_seen_orphan_at.
     *
     * @param mediaAssetId the media asset ID
     * @param claimToken the worker claim token
     * @param lastError error description (will be safely bounded/truncated)
     * @param now current mutation timestamp
     * @return true if the claim owner transitioned the row back to PENDING, false otherwise
     */
    boolean releaseClaimForRetry(UUID mediaAssetId, UUID claimToken, String lastError, Instant now);

    /**
     * Releases a claimed DELETING epoch following a transient Media delete failure, keeping status
     * as DELETING while clearing claim token and lock timestamp, incrementing retry count.
     *
     * @param mediaAssetId the media asset ID
     * @param claimToken the worker claim token
     * @param lastError error description (will be safely bounded/truncated)
     * @param now current mutation timestamp
     * @return true if the claim owner cleared the token for retry, false otherwise
     */
    boolean releaseDeletingClaimForRetry(UUID mediaAssetId, UUID claimToken, String lastError, Instant now);

    /**
     * Retrieves stale or unassigned DELETING candidates eligible for retry.
     *
     * @param leaseCutoff lease expiration cutoff (inclusive)
     * @param limit maximum number of candidates to return
     * @return deterministic list of DELETING orphan records ordered by (updated_at ASC, media_asset_id ASC)
     */
    List<WikiCoverOrphanRecord> findStaleDeletingCandidates(Instant leaseCutoff, int limit);

    /**
     * Claims a stale or unassigned DELETING candidate for retry.
     *
     * @param mediaAssetId the media asset ID
     * @param leaseCutoff lease expiration cutoff (inclusive)
     * @param claimToken unique worker lease token
     * @param now current mutation timestamp
     * @return true if claim was acquired, false otherwise
     */
    boolean claimDeletingForRetry(UUID mediaAssetId, Instant leaseCutoff, UUID claimToken, Instant now);

    /**
     * Recovers stale PROCESSING rows whose lease has expired back to PENDING, clearing claim token
     * and lock timestamp while preserving first_seen_orphan_at, retry_count, and last_error.
     * Explicitly ignores DELETING rows.
     *
     * @param leaseCutoff lease expiration cutoff (inclusive)
     * @param now current mutation timestamp
     * @return number of stale rows recovered
     */
    int recoverStaleProcessing(Instant leaseCutoff, Instant now);

    /**
     * Retrieves the operational state of an orphan epoch by media asset ID, if present.
     *
     * @param mediaAssetId the media asset ID
     * @return Optional containing the orphan record, or empty if absent
     */
    Optional<WikiCoverOrphanRecord> findByMediaAssetId(UUID mediaAssetId);
}
