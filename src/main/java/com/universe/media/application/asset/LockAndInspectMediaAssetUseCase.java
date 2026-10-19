package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Internal use case to atomically lock and inspect a MediaAsset for update
 * within an existing calling transaction boundary.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Active outer transaction requirement (Propagation.MANDATORY).</li>
 *   <li>Pessimistic exclusive row lock on the MediaAsset row via {@code findByIdForUpdate}.</li>
 *   <li>Asset existence check (distinguishing missing asset from existing DELETED asset).</li>
 *   <li>Optional media type validation (e.g. VIDEO).</li>
 *   <li>Read of current lifecycle status (ACTIVE, DELETED, ARCHIVED) under row lock.</li>
 * </ul>
 *
 * <p>The acquired InnoDB row lock remains held continuously until the calling transaction
 * commits or rolls back.
 */
@Service
public class LockAndInspectMediaAssetUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;

    public LockAndInspectMediaAssetUseCase(MediaAssetRepositoryPort mediaAssetRepositoryPort) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort,
                "MediaAssetRepositoryPort cannot be null."
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MediaAssetInspectionResult execute(UUID assetId, MediaType expectedType) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");

        MediaAsset asset = mediaAssetRepositoryPort
                .findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (expectedType != null && asset.getMediaType() != expectedType) {
            throw new IllegalArgumentException(
                    "Media asset [" + assetId + "] is type " + asset.getMediaType()
                            + ", expected " + expectedType
            );
        }

        return new MediaAssetInspectionResult(
                asset.getId(),
                asset.getMediaType(),
                asset.getVisibility(),
                asset.getStatus(),
                asset.getCurrentVersionNumber()
        );
    }
}
