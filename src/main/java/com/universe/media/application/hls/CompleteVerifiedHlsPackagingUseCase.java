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
 * Internal Media-only completion fencing guard accepting a {@link VerifiedHlsPackageReceipt}.
 *
 * <p><strong>HLS-1 Dormancy Rule:</strong> HLS-1 contains no FFmpeg worker or remote R2 verifier,
 * so no production endpoint or MediaContract method invokes this service in HLS-1.
 * Real artifact-verification-to-READY wiring belongs strictly to HLS-2.
 *
 * <p>Enforces strict Global Lock Order:
 * MediaAsset (FOR UPDATE) -> MediaVideoPackagingJob (FOR UPDATE) -> MediaVideoHlsPackage (FOR UPDATE, id ASC).
 */
@Service
public class CompleteVerifiedHlsPackagingUseCase {

    public static final Duration SUPERSEDED_RETENTION_GRACE = Duration.ofDays(7);

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort;
    private final MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort;
    private final ClockPort clockPort;

    public CompleteVerifiedHlsPackagingUseCase(
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
    public MediaVideoHlsPackage completeWithVerifiedReceipt(
            UUID assetId,
            UUID jobId,
            UUID packageId,
            String workerId,
            long expectedLeaseGeneration,
            VerifiedHlsPackageReceipt verifiedReceipt
    ) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        Objects.requireNonNull(jobId, "Job ID cannot be null.");
        Objects.requireNonNull(packageId, "Package ID cannot be null.");
        Objects.requireNonNull(workerId, "workerId cannot be null.");
        Objects.requireNonNull(verifiedReceipt, "VerifiedHlsPackageReceipt cannot be null.");

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

        // Fencing and expiration validation (leaseExpiresAt > now)
        if (job.getStatus() != MediaVideoPackagingJobStatus.LEASED
                || job.getLeaseGeneration() != expectedLeaseGeneration
                || !workerId.trim().equals(job.getLeaseOwner())
                || !packageId.equals(job.getCurrentPackageId())
                || !job.isLeaseActiveAt(now)) {
            throw new StalePackagingLeaseException(
                    "Stale or expired lease completing job [" + jobId + "] (worker=" + workerId
                            + ", expectedGen=" + expectedLeaseGeneration + ", actualGen=" + job.getLeaseGeneration()
                            + ", leaseExpiresAt=" + job.getLeaseExpiresAt() + ", now=" + now + ")."
            );
        }

        // Authoritative asset lifecycle and source-version currency validation
        if (asset.getStatus() != MediaAssetStatus.ACTIVE) {
            throw new StalePackagingLeaseException(
                    "Cannot complete HLS package for non-ACTIVE asset [" + assetId + "] (status=" + asset.getStatus() + ")."
            );
        }
        if (asset.getCurrentVersionNumber() != job.getSourceVersionNumber()) {
            throw new StalePackagingLeaseException(
                    "Stale source version for asset [" + assetId + "]: job version=" + job.getSourceVersionNumber()
                            + ", current=" + asset.getCurrentVersionNumber() + "."
            );
        }

        // STEP 3: Package locks THIRD (ordered by id ASC)
        List<MediaVideoHlsPackage> versionPackages =
                hlsPackageRepositoryPort.findBySourceVersionIdForUpdateOrderByIdAsc(job.getSourceVersionId());

        MediaVideoHlsPackage targetPackage = null;
        for (MediaVideoHlsPackage pkg : versionPackages) {
            if (pkg.getId().equals(packageId)) {
                targetPackage = pkg;
                break;
            }
        }

        if (targetPackage == null) {
            throw new IllegalStateException("Target attempt package not found: " + packageId);
        }
        if (targetPackage.getStatus() != MediaVideoHlsPackageStatus.PROCESSING) {
            throw new StalePackagingLeaseException(
                    "Target HLS package [" + packageId + "] is not PROCESSING (status=" + targetPackage.getStatus() + ")."
            );
        }

        for (MediaVideoHlsPackage pkg : versionPackages) {
            if (!pkg.getId().equals(packageId) && pkg.getStatus() == MediaVideoHlsPackageStatus.READY) {
                pkg.markSuperseded(now.plus(SUPERSEDED_RETENTION_GRACE), now);
                hlsPackageRepositoryPort.save(pkg);
            }
        }

        targetPackage.markReady(
                verifiedReceipt.masterManifestSha256(),
                verifiedReceipt.totalSizeBytes(),
                verifiedReceipt.objectCount(),
                verifiedReceipt.renditionsJson(),
                verifiedReceipt.verifiedAt(),
                now
        );
        MediaVideoHlsPackage savedPackage = hlsPackageRepositoryPort.save(targetPackage);

        job.markSucceeded(workerId.trim(), expectedLeaseGeneration, packageId, now);
        packagingJobRepositoryPort.save(job);

        return savedPackage;
    }
}
