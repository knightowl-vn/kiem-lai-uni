package com.universe.media.application.hls;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.StalePackagingLeaseException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVideoHlsPackage;
import com.universe.media.domain.MediaVideoHlsPackageStatus;
import com.universe.media.domain.MediaVideoPackagingJob;
import com.universe.media.domain.MediaVideoPackagingJobStatus;
import com.universe.media.domain.PackagingFailureDisposition;
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
        Objects.requireNonNull(retryBackoff, "retryBackoff cannot be null.");
        return executeInTransaction(
                assetId,
                jobId,
                workerId,
                expectedLeaseGeneration,
                PackagingFailureDisposition.TRANSIENT_RETRYABLE,
                errorReason,
                retryBackoff
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MediaVideoPackagingJob execute(
            UUID assetId,
            UUID jobId,
            String workerId,
            long expectedLeaseGeneration,
            PackagingFailureDisposition disposition,
            String errorReason,
            Duration retryBackoff
    ) {
        Objects.requireNonNull(disposition, "PackagingFailureDisposition cannot be null.");
        if (disposition == PackagingFailureDisposition.TRANSIENT_RETRYABLE) {
            Objects.requireNonNull(retryBackoff, "retryBackoff cannot be null for transient failures.");
        }
        return executeInTransaction(
                assetId,
                jobId,
                workerId,
                expectedLeaseGeneration,
                disposition,
                errorReason,
                retryBackoff
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MediaVideoPackagingJob executePermanentFailure(
            UUID assetId,
            UUID jobId,
            String workerId,
            long expectedLeaseGeneration,
            String errorReason
    ) {
        return executeInTransaction(
                assetId,
                jobId,
                workerId,
                expectedLeaseGeneration,
                PackagingFailureDisposition.PERMANENT_NON_RETRYABLE,
                errorReason,
                null
        );
    }

    private MediaVideoPackagingJob executeInTransaction(
            UUID assetId,
            UUID jobId,
            String workerId,
            long expectedLeaseGeneration,
            PackagingFailureDisposition disposition,
            String errorReason,
            Duration retryBackoff
    ) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        Objects.requireNonNull(jobId, "Job ID cannot be null.");
        Objects.requireNonNull(workerId, "workerId cannot be null.");

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
        String trimmedWorker = workerId.trim();
        if (job.getStatus() != MediaVideoPackagingJobStatus.LEASED
                || job.getLeaseGeneration() != expectedLeaseGeneration
                || !trimmedWorker.equals(job.getLeaseOwner())
                || !job.isLeaseActiveAt(now)) {
            throw new StalePackagingLeaseException(
                    "Cannot fail job [" + jobId + "] with stale or expired lease (worker=" + workerId
                            + ", gen=" + expectedLeaseGeneration + ", leaseExpiresAt=" + job.getLeaseExpiresAt()
                            + ", now=" + now + ")."
            );
        }

        // Prevent outdated/deleted asset state from being misclassified as a permanent media defect
        if (asset.getStatus() != MediaAssetStatus.ACTIVE
                || asset.getCurrentVersionNumber() != job.getSourceVersionNumber()) {
            throw new StalePackagingLeaseException(
                    "Cannot record packaging failure against non-ACTIVE or superseded source version for asset ["
                            + assetId + "] (status=" + asset.getStatus()
                            + ", currentVersion=" + asset.getCurrentVersionNumber()
                            + ", jobVersion=" + job.getSourceVersionNumber() + ")."
            );
        }

        // STEP 3: Package lock THIRD
        Instant packagePurgeAfter = MediaVideoHlsPackage.computeFailedPurgeAfter(now);
        if (job.getCurrentPackageId() != null) {
            hlsPackageRepositoryPort.findByIdForUpdate(job.getCurrentPackageId()).ifPresent(pkg -> {
                if (pkg.getStatus() == MediaVideoHlsPackageStatus.PROCESSING) {
                    pkg.markFailed(errorReason, packagePurgeAfter, now);
                    hlsPackageRepositoryPort.save(pkg);
                }
            });
        }

        if (disposition == PackagingFailureDisposition.PERMANENT_NON_RETRYABLE) {
            job.recordPermanentFailure(trimmedWorker, expectedLeaseGeneration, errorReason, now);
        } else {
            job.recordAttemptFailure(trimmedWorker, expectedLeaseGeneration, errorReason, retryBackoff, now);
        }
        return packagingJobRepositoryPort.save(job);
    }
}
