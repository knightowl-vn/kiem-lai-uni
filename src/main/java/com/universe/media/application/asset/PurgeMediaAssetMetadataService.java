package com.universe.media.application.asset;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Dedicated transactional service responsible for bottom-up metadata removal
 * after physical storage objects have been successfully purged.
 *
 * <p><strong>Foreign Key Order:</strong>
 * <ol>
 *     <li>Re-read asset state inside short write transaction (missing = idempotent success, non-DELETED = refuse).</li>
 *     <li>Load its current version IDs before bottom-up deletion.</li>
 *     <li>Delete derivative variants: {@code media_image_variants}</li>
 *     <li>Delete binary versions: {@code media_asset_versions}</li>
 *     <li>Delete asset root: {@code media_assets}</li>
 * </ol>
 */
@Service
public class PurgeMediaAssetMetadataService {

    private final MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;

    public PurgeMediaAssetMetadataService(
            MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            MediaAssetRepositoryPort mediaAssetRepositoryPort
    ) {
        this.mediaImageVariantRepositoryPort = Objects.requireNonNull(
                mediaImageVariantRepositoryPort,
                "MediaImageVariantRepositoryPort cannot be null."
        );
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(
                mediaAssetVersionRepositoryPort,
                "MediaAssetVersionRepositoryPort cannot be null."
        );
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort,
                "MediaAssetRepositoryPort cannot be null."
        );
    }

    @Transactional
    public void execute(UUID assetId) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");

        // 1. Re-read the asset inside the short write transaction
        Optional<MediaAsset> assetOpt = mediaAssetRepositoryPort.findById(assetId);
        if (assetOpt.isEmpty()) {
            // Missing asset = already purged / idempotent success
            return;
        }

        MediaAsset asset = assetOpt.get();
        if (asset.getStatus() != MediaAssetStatus.DELETED) {
            // Any other status = refuse deletion
            throw new IllegalStateException(
                    "Cannot purge media asset [ID: " + assetId + "] with status: " + asset.getStatus()
                            + ". Only DELETED assets may be purged from metadata."
            );
        }

        // DELETED = proceed
        // 2. Load current version IDs inside the transaction before bottom-up deletion
        List<MediaAssetVersion> versions = mediaAssetVersionRepositoryPort.findAllByAssetId(assetId);
        List<UUID> versionIds = versions.stream().map(MediaAssetVersion::getId).toList();

        // 3. Delete derivative variants (child of version)
        if (!versionIds.isEmpty()) {
            mediaImageVariantRepositoryPort.deleteByVersionIds(versionIds);
        }

        // 4. Delete binary versions (child of asset)
        mediaAssetVersionRepositoryPort.deleteByAssetId(assetId);

        // 5. Delete asset root
        mediaAssetRepositoryPort.deleteById(assetId);
    }
}
