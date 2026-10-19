package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Internal use case to atomically lock and validate an active MediaAsset for update
 * within an existing calling transaction boundary.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Active outer transaction requirement (Propagation.MANDATORY).</li>
 *   <li>Pessimistic exclusive row lock on the MediaAsset row via {@code findByIdForUpdate}.</li>
 *   <li>Asset existence and ACTIVE lifecycle status check.</li>
 *   <li>Optional media type validation.</li>
 *   <li>Resolution of authoritative current version snapshot.</li>
 * </ul>
 *
 * <p>The acquired InnoDB row lock remains held continuously until the calling transaction
 * commits or rolls back, providing shared fencing against concurrent mutations.
 */
@Service
public class LockAndValidateActiveMediaAssetUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    public LockAndValidateActiveMediaAssetUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort,
                "MediaAssetRepositoryPort cannot be null."
        );
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(
                mediaAssetVersionRepositoryPort,
                "MediaAssetVersionRepositoryPort cannot be null."
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MediaAssetDetailResult execute(UUID assetId, MediaType expectedType) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");

        MediaAsset asset = mediaAssetRepositoryPort
                .findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (asset.getStatus() != MediaAssetStatus.ACTIVE) {
            throw new MediaAssetNotFoundException(assetId);
        }

        if (expectedType != null && asset.getMediaType() != expectedType) {
            throw new IllegalArgumentException(
                    "Media asset [" + assetId + "] is type " + asset.getMediaType()
                            + ", expected " + expectedType
            );
        }

        MediaAssetVersion currentVersion = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(
                        asset.getId(),
                        asset.getCurrentVersionNumber()
                )
                .orElseThrow(() -> new MediaAssetVersionNotFoundException(
                        asset.getId(),
                        asset.getCurrentVersionNumber()
                ));

        MediaVersionItemResult versionItemResult = toVersionItemResult(currentVersion);

        return new MediaAssetDetailResult(
                asset.getId(),
                asset.getMediaType(),
                asset.getVisibility(),
                asset.getStatus(),
                asset.getCurrentVersionNumber(),
                asset.getCreatedAt(),
                asset.getUpdatedAt(),
                versionItemResult
        );
    }

    private MediaVersionItemResult toVersionItemResult(MediaAssetVersion version) {
        return new MediaVersionItemResult(
                version.getId(),
                version.getAssetId(),
                version.getVersionNumber(),
                version.getStorageLocation().providerId().value(),
                version.getStorageLocation().key().value(),
                version.getPublicUrl(),
                version.getContentHash().value(),
                version.getMimeType().value(),
                version.getSizeBytes(),
                version.getOriginalFilename(),
                version.getCreatedAt()
        );
    }
}
