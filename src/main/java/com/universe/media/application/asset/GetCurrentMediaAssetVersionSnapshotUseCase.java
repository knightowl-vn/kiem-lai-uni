package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class GetCurrentMediaAssetVersionSnapshotUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    public GetCurrentMediaAssetVersionSnapshotUseCase(
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

    @Transactional(readOnly = true)
    public MediaAssetVersionSnapshotResult execute(
            GetCurrentMediaAssetVersionSnapshotQuery query
    ) {
        Objects.requireNonNull(query, "GetCurrentMediaAssetVersionSnapshotQuery cannot be null.");
        UUID assetId = Objects.requireNonNull(query.assetId(), "Asset ID cannot be null.");

        MediaAsset asset = mediaAssetRepositoryPort.findById(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));
        requireInternalConsumerEligible(asset);

        MediaAssetVersion version = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(assetId, asset.getCurrentVersionNumber())
                .orElseThrow(() -> new MediaAssetVersionNotFoundException(assetId, asset.getCurrentVersionNumber()));

        return toSnapshotResult(version);
    }

    static void requireInternalConsumerEligible(MediaAsset asset) {
        if (asset.getStatus() == MediaAssetStatus.DELETED) {
            throw new MediaAssetNotFoundException(asset.getId());
        }
    }

    static MediaAssetVersionSnapshotResult toSnapshotResult(MediaAssetVersion version) {
        return new MediaAssetVersionSnapshotResult(
                version.getAssetId(),
                version.getVersionNumber(),
                version.getContentHash().value(),
                version.getMimeType().value(),
                version.getSizeBytes(),
                version.getOriginalFilename()
        );
    }
}
