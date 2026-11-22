package com.universe.media.application.hls;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaVideoHlsPackage;
import com.universe.media.domain.MediaVideoHlsPackageStatus;
import com.universe.media.domain.MediaVideoPackagingJob;
import com.universe.media.domain.MediaVideoPackagingJobStatus;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Cancels or supersedes HLS packaging jobs and packages for version replacement, archival, or asset
 * deletion while strictly adhering to the Global Lock Order:
 * MediaAsset (FOR UPDATE) -> MediaVideoPackagingJob (FOR UPDATE, id ASC) -> MediaVideoHlsPackage (FOR UPDATE, id ASC).
 */
@Service
public class CancelOrSupersedeMediaVideoPackagingUseCase {

    public static final Duration SUPERSEDED_RETENTION_GRACE = Duration.ofDays(7);

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort;
    private final MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort;
    private final ClockPort clockPort;

    public CancelOrSupersedeMediaVideoPackagingUseCase(
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

    /**
     * Standalone transactional entry point that cancels active jobs and abandons/supersedes packages
     * belonging to source versions older than {@code newAuthoritativeVersionNumber}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelAndSupersedeOlderVersions(
            UUID assetId,
            int newAuthoritativeVersionNumber,
            String reason
    ) {
        executeCancelAndSupersedeOlderVersions(assetId, newAuthoritativeVersionNumber, reason);
    }

    /**
     * Joins an already-active Media transaction (holding parent {@code MediaAsset FOR UPDATE}) to cancel
     * and supersede HLS work for versions older than {@code newAuthoritativeVersionNumber} atomically.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelAndSupersedeOlderVersionsInCurrentTransaction(
            UUID assetId,
            int newAuthoritativeVersionNumber,
            String reason
    ) {
        executeCancelAndSupersedeOlderVersions(assetId, newAuthoritativeVersionNumber, reason);
    }

    /**
     * Standalone transactional entry point that cancels all active/failed jobs and marks non-terminal
     * packages ABANDONED/SUPERSEDED upon asset deletion.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelAllForAssetDeletion(UUID assetId, String reason) {
        executeCancelAllForAssetDeletion(assetId, reason);
    }

    /**
     * Joins an already-active Media deletion transaction (holding parent {@code MediaAsset FOR UPDATE})
     * to cancel HLS work and supersede READY packages atomically with soft deletion.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelAllForAssetDeletionInCurrentTransaction(UUID assetId, String reason) {
        executeCancelAllForAssetDeletion(assetId, reason);
    }

    /**
     * Joins an already-active Media archival transaction (holding parent {@code MediaAsset FOR UPDATE})
     * to cancel active PENDING/LEASED jobs and abandon in-flight PROCESSING packages while preserving
     * READY and terminal packages intact.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelActivePackagingForArchiveInCurrentTransaction(UUID assetId, String reason) {
        executeCancelActivePackagingForArchive(assetId, reason);
    }

    private void executeCancelAndSupersedeOlderVersions(
            UUID assetId,
            int newAuthoritativeVersionNumber,
            String reason
    ) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        if (newAuthoritativeVersionNumber < 1) {
            throw new IllegalArgumentException("newAuthoritativeVersionNumber must be >= 1.");
        }

        // STEP 1: MediaAsset lock FIRST
        mediaAssetRepositoryPort.findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        Instant now = clockPort.now();
        Instant purgeAfter = MediaVideoHlsPackage.computeSupersededPurgeAfter(now);
        String effectiveReason = reason != null ? reason : "Superseded by asset version " + newAuthoritativeVersionNumber;

        // STEP 2: Job locks SECOND (id ASC)
        List<MediaVideoPackagingJob> jobs = packagingJobRepositoryPort.findByAssetIdForUpdateOrderByIdAsc(assetId);
        for (MediaVideoPackagingJob job : jobs) {
            if (job.getSourceVersionNumber() < newAuthoritativeVersionNumber
                    && (job.getStatus() == MediaVideoPackagingJobStatus.PENDING
                    || job.getStatus() == MediaVideoPackagingJobStatus.LEASED)) {
                job.cancel(effectiveReason, now);
                packagingJobRepositoryPort.save(job);
            }
        }

        // STEP 3: Package locks THIRD (id ASC)
        List<MediaVideoHlsPackage> packages = hlsPackageRepositoryPort.findByAssetIdForUpdateOrderByIdAsc(assetId);
        for (MediaVideoHlsPackage pkg : packages) {
            if (pkg.getSourceVersionNumber() >= newAuthoritativeVersionNumber) {
                continue;
            }
            abandonOrSupersedePackage(pkg, effectiveReason, purgeAfter, purgeAfter, now);
        }
    }

    private void executeCancelAllForAssetDeletion(UUID assetId, String reason) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");

        // STEP 1: MediaAsset lock FIRST
        MediaAsset asset = mediaAssetRepositoryPort.findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        Instant now = clockPort.now();
        Instant purgeAfter = MediaVideoHlsPackage.computeSupersededPurgeAfter(now);
        String effectiveReason = reason != null ? reason : "Asset [" + asset.getId() + "] deleted/cancelled.";

        // STEP 2: Job locks SECOND (id ASC)
        List<MediaVideoPackagingJob> jobs = packagingJobRepositoryPort.findByAssetIdForUpdateOrderByIdAsc(assetId);
        for (MediaVideoPackagingJob job : jobs) {
            if (job.getStatus() == MediaVideoPackagingJobStatus.PENDING
                    || job.getStatus() == MediaVideoPackagingJobStatus.LEASED
                    || job.getStatus() == MediaVideoPackagingJobStatus.FAILED) {
                job.cancel(effectiveReason, now);
                packagingJobRepositoryPort.save(job);
            }
        }

        // STEP 3: Package locks THIRD (id ASC)
        List<MediaVideoHlsPackage> packages = hlsPackageRepositoryPort.findByAssetIdForUpdateOrderByIdAsc(assetId);
        for (MediaVideoHlsPackage pkg : packages) {
            abandonOrSupersedePackage(pkg, effectiveReason, purgeAfter, purgeAfter, now);
        }
    }

    private void executeCancelActivePackagingForArchive(UUID assetId, String reason) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");

        // STEP 1: MediaAsset lock FIRST
        MediaAsset asset = mediaAssetRepositoryPort.findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        Instant now = clockPort.now();
        Instant abandonPurgeAfter = MediaVideoHlsPackage.computeAbandonedOnReclaimPurgeAfter(now);
        String effectiveReason = reason != null ? reason : "Asset [" + asset.getId() + "] archived.";

        // STEP 2: Job locks SECOND (id ASC) - cancel only active PENDING/LEASED work
        List<MediaVideoPackagingJob> jobs = packagingJobRepositoryPort.findByAssetIdForUpdateOrderByIdAsc(assetId);
        for (MediaVideoPackagingJob job : jobs) {
            if (job.getStatus() == MediaVideoPackagingJobStatus.PENDING
                    || job.getStatus() == MediaVideoPackagingJobStatus.LEASED) {
                job.cancel(effectiveReason, now);
                packagingJobRepositoryPort.save(job);
            }
        }

        // STEP 3: Package locks THIRD (id ASC) - abandon PROCESSING only; preserve READY & terminal states
        List<MediaVideoHlsPackage> packages = hlsPackageRepositoryPort.findByAssetIdForUpdateOrderByIdAsc(assetId);
        for (MediaVideoHlsPackage pkg : packages) {
            if (pkg.getStatus() == MediaVideoHlsPackageStatus.PROCESSING) {
                pkg.markAbandoned(effectiveReason, abandonPurgeAfter, now);
                hlsPackageRepositoryPort.save(pkg);
            }
        }
    }

    private void abandonOrSupersedePackage(
            MediaVideoHlsPackage pkg,
            String effectiveReason,
            Instant abandonPurgeAfter,
            Instant supersedePurgeAfter,
            Instant now
    ) {
        if (pkg.getStatus() == MediaVideoHlsPackageStatus.PROCESSING) {
            pkg.markAbandoned(effectiveReason, abandonPurgeAfter, now);
            hlsPackageRepositoryPort.save(pkg);
        } else if (pkg.getStatus() == MediaVideoHlsPackageStatus.READY) {
            pkg.markSuperseded(supersedePurgeAfter, now);
            hlsPackageRepositoryPort.save(pkg);
        }
    }
}
