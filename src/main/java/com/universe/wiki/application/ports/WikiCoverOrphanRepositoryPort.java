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
     * Recovers stale PROCESSING rows whose lease has expired back to PENDING, clearing claim token
     * and lock timestamp while preserving first_seen_orphan_at, retry_count, and last_error.
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
