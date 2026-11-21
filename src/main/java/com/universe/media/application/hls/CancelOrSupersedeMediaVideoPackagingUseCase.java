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
 * Cancels or supersedes HLS packaging jobs and packages for version replacement or asset deletion
 * while strictly adhering to the Global Lock Order:
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
     * Cancels active jobs and abandons/supersedes packages belonging to source versions older than
     * {@code newAuthoritativeVersionNumber}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelAndSupersedeOlderVersions(
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
        Instant purgeAfter = now.plus(SUPERSEDED_RETENTION_GRACE);

        // STEP 2: Job locks SECOND (id ASC)
        List<MediaVideoPackagingJob> jobs = packagingJobRepositoryPort.findByAssetIdForUpdateOrderByIdAsc(assetId);
        for (MediaVideoPackagingJob job : jobs) {
            if (job.getSourceVersionNumber() < newAuthoritativeVersionNumber
                    && (job.getStatus() == MediaVideoPackagingJobStatus.PENDING
                    || job.getStatus() == MediaVideoPackagingJobStatus.LEASED)) {
                job.cancel(reason != null ? reason : "Superseded by asset version " + newAuthoritativeVersionNumber, now);
                packagingJobRepositoryPort.save(job);
            }
        }

        // STEP 3: Package locks THIRD (id ASC)
        List<MediaVideoHlsPackage> packages = hlsPackageRepositoryPort.findByAssetIdForUpdateOrderByIdAsc(assetId);
        for (MediaVideoHlsPackage pkg : packages) {
            if (pkg.getSourceVersionNumber() >= newAuthoritativeVersionNumber) {
                continue;
            }
            if (pkg.getStatus() == MediaVideoHlsPackageStatus.PROCESSING) {
                pkg.markAbandoned(reason != null ? reason : "Superseded by version " + newAuthoritativeVersionNumber, purgeAfter, now);
                hlsPackageRepositoryPort.save(pkg);
            } else if (pkg.getStatus() == MediaVideoHlsPackageStatus.READY) {
                pkg.markSuperseded(purgeAfter, now);
                hlsPackageRepositoryPort.save(pkg);
            }
        }
    }

    /**
     * Cancels all active jobs and marks all non-terminal packages ABANDONED/SUPERSEDED upon asset deletion.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelAllForAssetDeletion(UUID assetId, String reason) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");

        // STEP 1: MediaAsset lock FIRST
        MediaAsset asset = mediaAssetRepositoryPort.findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        Instant now = clockPort.now();
        Instant purgeAfter = now.plus(SUPERSEDED_RETENTION_GRACE);
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
            if (pkg.getStatus() == MediaVideoHlsPackageStatus.PROCESSING) {
                pkg.markAbandoned(effectiveReason, purgeAfter, now);
                hlsPackageRepositoryPort.save(pkg);
            } else if (pkg.getStatus() == MediaVideoHlsPackageStatus.READY) {
                pkg.markSuperseded(purgeAfter, now);
                hlsPackageRepositoryPort.save(pkg);
            }
        }
    }
}
