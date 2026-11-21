package com.universe.media.application.hls;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVideoPackagingJob;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Enqueues a PENDING HLS packaging job for the authoritative current version of an ACTIVE VIDEO asset.
 * Enforces Global Lock Order: MediaAsset (FOR UPDATE) -> MediaVideoPackagingJob.
 */
@Service
public class CreateMediaVideoPackagingJobUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort;
    private final ClockPort clockPort;

    public CreateMediaVideoPackagingJobUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort,
            ClockPort clockPort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(mediaAssetRepositoryPort, "MediaAssetRepositoryPort cannot be null.");
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(mediaAssetVersionRepositoryPort, "MediaAssetVersionRepositoryPort cannot be null.");
        this.packagingJobRepositoryPort = Objects.requireNonNull(packagingJobRepositoryPort, "MediaVideoPackagingJobRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional
    public MediaVideoPackagingJob execute(UUID assetId, int maxAttempts) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }

        // STEP 1: MediaAsset lock FIRST
        MediaAsset asset = mediaAssetRepositoryPort.findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (asset.getStatus() != MediaAssetStatus.ACTIVE) {
            throw new IllegalStateException("Cannot create HLS packaging job for non-ACTIVE asset [" + assetId + "]: " + asset.getStatus());
        }
        if (asset.getMediaType() != MediaType.VIDEO) {
            throw new IllegalArgumentException("HLS packaging is supported only for VIDEO assets, found: " + asset.getMediaType());
        }

        int currentVersionNumber = asset.getCurrentVersionNumber();
        MediaAssetVersion currentVersion = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(assetId, currentVersionNumber)
                .orElseThrow(() -> new MediaAssetVersionNotFoundException(assetId, currentVersionNumber));

        // STEP 2: Re-use existing active job idempotently if present
        Optional<MediaVideoPackagingJob> existingActive =
                packagingJobRepositoryPort.findActiveBySourceVersionIdForUpdate(currentVersion.getId());
        if (existingActive.isPresent()) {
            return existingActive.get();
        }

        Instant now = clockPort.now();
        MediaVideoPackagingJob job = MediaVideoPackagingJob.createPending(
                UUID.randomUUID(),
                assetId,
                currentVersion.getId(),
                currentVersionNumber,
                maxAttempts,
                now
        );

        return packagingJobRepositoryPort.save(job);
    }

    @Transactional
    public MediaVideoPackagingJob execute(UUID assetId) {
        return execute(assetId, MediaVideoPackagingJob.DEFAULT_MAX_ATTEMPTS);
    }
}
