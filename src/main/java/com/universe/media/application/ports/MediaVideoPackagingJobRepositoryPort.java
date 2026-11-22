package com.universe.media.application.ports;

import com.universe.media.domain.MediaVideoPackagingJob;
import com.universe.media.domain.MediaVideoPackagingJobStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaVideoPackagingJobRepositoryPort {

    MediaVideoPackagingJob save(MediaVideoPackagingJob job);

    Optional<MediaVideoPackagingJob> findById(UUID id);

    Optional<MediaVideoPackagingJob> findByIdForUpdate(UUID id);

    Optional<MediaVideoPackagingJob> findActiveBySourceVersionIdForUpdate(UUID sourceVersionId);

    /**
     * Checks whether any terminal packaging job (SUCCEEDED, FAILED, CANCELLED) exists for
     * {@code sourceVersionId} without hydrating full job rows, returning the highest-priority
     * status present (SUCCEEDED > FAILED > CANCELLED).
     */
    Optional<MediaVideoPackagingJobStatus> findHighestPriorityTerminalStatusBySourceVersionId(UUID sourceVersionId);

    List<MediaVideoPackagingJob> findByAssetIdForUpdateOrderByIdAsc(UUID assetId);

    /**
     * Bounded non-locking candidate query selecting PENDING (due) or expired LEASED jobs.
     */
    List<MediaVideoPackagingJobCandidate> findClaimableCandidates(Instant now, int limit);

    int updateHeartbeatIfLeaseMatches(
            UUID jobId,
            String leaseOwner,
            long leaseGeneration,
            Instant now,
            Instant heartbeatAt,
            Instant leaseExpiresAt,
            Instant updatedAt
    );

    boolean existsByAssetId(UUID assetId);
}
