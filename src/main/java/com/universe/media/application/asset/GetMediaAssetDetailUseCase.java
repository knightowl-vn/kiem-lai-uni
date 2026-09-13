package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class GetMediaAssetDetailUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    public GetMediaAssetDetailUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort
    ) {
        this.mediaAssetRepositoryPort =
                Objects.requireNonNull(
                        mediaAssetRepositoryPort,
                        "MediaAssetRepositoryPort cannot be null."
                );
        this.mediaAssetVersionRepositoryPort =
                Objects.requireNonNull(
                        mediaAssetVersionRepositoryPort,
                        "MediaAssetVersionRepositoryPort cannot be null."
                );
    }

    @Transactional(readOnly = true)
    public MediaAssetDetailResult execute(
            GetMediaAssetDetailQuery query
    ) {
        Objects.requireNonNull(
                query,
                "GetMediaAssetDetailQuery cannot be null."
        );

        UUID assetId =
                Objects.requireNonNull(
                        query.assetId(),
                        "Asset ID cannot be null."
                );

        MediaAsset asset =
                mediaAssetRepositoryPort
                        .findById(assetId)
                        .orElseThrow(() ->
                                new MediaAssetNotFoundException(assetId)
                        );

        MediaAssetVersion currentVersion =
                mediaAssetVersionRepositoryPort
                        .findByAssetIdAndVersionNumber(
                                asset.getId(),
                                asset.getCurrentVersionNumber()
                        )
                        .orElseThrow(() ->
                                new MediaAssetVersionNotFoundException(
                                        asset.getId(),
                                        asset.getCurrentVersionNumber()
                                )
                        );

        MediaVersionItemResult versionItemResult =
                toVersionItemResult(currentVersion);

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

    private MediaVersionItemResult toVersionItemResult(
            MediaAssetVersion version
    ) {
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
