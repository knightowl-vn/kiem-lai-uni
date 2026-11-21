package com.universe.media.application.asset;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import org.springframework.beans.factory.annotation.Autowired;
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
 *     <li>Lock and re-read asset state inside short write transaction (missing = idempotent success, non-DELETED = refuse).</li>
 *     <li>Fail closed if any HLS package or packaging job metadata still exists for the asset.</li>
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
    private final MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort;
    private final MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort;

    @Autowired
    public PurgeMediaAssetMetadataService(
            MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort,
            MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort
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
        // Retained directly so runtime execute(...) also fails closed explicitly if constructed programmatically with null.
        this.hlsPackageRepositoryPort = hlsPackageRepositoryPort;
        this.packagingJobRepositoryPort = packagingJobRepositoryPort;
    }

    @Transactional
    public void execute(UUID assetId) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");

        if (hlsPackageRepositoryPort == null) {
            throw new IllegalStateException(
                    "Cannot purge media asset [ID: " + assetId
                            + "]: required MediaVideoHlsPackageRepositoryPort is unavailable (fail-closed)."
            );
        }
        if (packagingJobRepositoryPort == null) {
            throw new IllegalStateException(
                    "Cannot purge media asset [ID: " + assetId
                            + "]: required MediaVideoPackagingJobRepositoryPort is unavailable (fail-closed)."
            );
        }

        // 1. Lock and re-read the asset inside the short write transaction (MediaAsset FOR UPDATE FIRST)
        Optional<MediaAsset> assetOpt = mediaAssetRepositoryPort.findByIdForUpdate(assetId);
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

        // 1b. Fail-closed protection against orphaned or active HLS packaging state
        if (hlsPackageRepositoryPort.existsByAssetId(assetId)
                || packagingJobRepositoryPort.existsByAssetId(assetId)) {
            throw new IllegalStateException(
                    "Cannot purge media asset [ID: " + assetId
                            + "]: HLS packaging jobs or packages still exist and must be cleaned up before physical purge."
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
