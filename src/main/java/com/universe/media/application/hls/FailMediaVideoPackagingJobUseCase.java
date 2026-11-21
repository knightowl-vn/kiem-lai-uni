package com.universe.media.application.hls;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.StalePackagingLeaseException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaVideoHlsPackageStatus;
import com.universe.media.domain.MediaVideoPackagingJob;
import com.universe.media.domain.MediaVideoPackagingJobStatus;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Records a fenced failure for an active, unexpired packaging lease using strict lock order:
 * MediaAsset (FOR UPDATE) -> MediaVideoPackagingJob (FOR UPDATE) -> MediaVideoHlsPackage (FOR UPDATE).
 *
 * <p><strong>Expired Lease Policy:</strong> Once a lease reaches or passes expiration
 * ({@code leaseExpiresAt <= now}), live worker failure reporting is rejected as stale
 * ({@link StalePackagingLeaseException}) so an expired worker cannot mutate retry backoff or
 * attempt metadata. Recovery/transition of expired leases belongs to {@link ClaimMediaVideoPackagingJobUseCase}
 * reclaim (or dedicated HLS-2 timeout sweeping).
 */
@Service
public class FailMediaVideoPackagingJobUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort;
    private final MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort;
    private final ClockPort clockPort;

    public FailMediaVideoPackagingJobUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort,
            MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort,
            ClockPort clockPort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(mediaAssetRepositoryPort, "MediaAssetRepositoryPort cannot be null.");
        this.packagingJobRepositoryPort = Objects.requireNonNull(packagingJobRepositoryPort, "MediaVideoPackagingJobRepositoryPort cannot be null.");
        this.hlsPackageRepositoryPort = Objects.requireNonNull(hlsPackageRepositoryPort, "MediaVideoHlsPackageRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MediaVideoPackagingJob execute(
            UUID assetId,
            UUID jobId,
            String workerId,
            long expectedLeaseGeneration,
            String errorReason,
            Duration retryBackoff
    ) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        Objects.requireNonNull(jobId, "Job ID cannot be null.");
        Objects.requireNonNull(workerId, "workerId cannot be null.");
        Objects.requireNonNull(retryBackoff, "retryBackoff cannot be null.");

        // STEP 1: MediaAsset lock FIRST
        MediaAsset asset = mediaAssetRepositoryPort.findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        // STEP 2: Job lock SECOND
        MediaVideoPackagingJob job = packagingJobRepositoryPort.findByIdForUpdate(jobId)
                .orElseThrow(() -> new IllegalStateException("Packaging job not found: " + jobId));

        if (!asset.getId().equals(job.getAssetId())) {
            throw new IllegalArgumentException("Job [" + jobId + "] does not belong to asset [" + assetId + "].");
        }
        Instant now = clockPort.now();
        if (job.getStatus() != MediaVideoPackagingJobStatus.LEASED
                || job.getLeaseGeneration() != expectedLeaseGeneration
                || !workerId.trim().equals(job.getLeaseOwner())
                || !job.isLeaseActiveAt(now)) {
            throw new StalePackagingLeaseException(
                    "Cannot fail job [" + jobId + "] with stale or expired lease (worker=" + workerId
                            + ", gen=" + expectedLeaseGeneration + ", leaseExpiresAt=" + job.getLeaseExpiresAt()
                            + ", now=" + now + ")."
            );
        }

        // STEP 3: Package lock THIRD
        if (job.getCurrentPackageId() != null) {
            hlsPackageRepositoryPort.findByIdForUpdate(job.getCurrentPackageId()).ifPresent(pkg -> {
                if (pkg.getStatus() == MediaVideoHlsPackageStatus.PROCESSING) {
                    pkg.markFailed(errorReason, now, now);
                    hlsPackageRepositoryPort.save(pkg);
                }
            });
        }

        job.recordAttemptFailure(workerId.trim(), expectedLeaseGeneration, errorReason, retryBackoff, now);
        return packagingJobRepositoryPort.save(job);
    }
}
