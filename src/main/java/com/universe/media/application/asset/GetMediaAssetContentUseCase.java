package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaVisibility;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case for retrieving the binary content stream of an ACTIVE and PUBLIC media asset.
 * <p>
 * <strong>Stream Ownership:</strong> The caller/delivery layer is responsible for closing the
 * {@link InputStream} contained within {@link GetMediaAssetContentResult}.
 */
@Service
public class GetMediaAssetContentUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final BinaryStoragePort binaryStoragePort;

    public GetMediaAssetContentUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            BinaryStoragePort binaryStoragePort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort,
                "MediaAssetRepositoryPort cannot be null."
        );
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(
                mediaAssetVersionRepositoryPort,
                "MediaAssetVersionRepositoryPort cannot be null."
        );
        this.binaryStoragePort = Objects.requireNonNull(
                binaryStoragePort,
                "BinaryStoragePort cannot be null."
        );
    }

    @Transactional(readOnly = true)
    public GetMediaAssetContentResult execute(
            GetMediaAssetContentQuery query
    ) {
        Objects.requireNonNull(query, "GetMediaAssetContentQuery cannot be null.");

        UUID assetId = query.assetId();

        MediaAsset asset = mediaAssetRepositoryPort.findById(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (asset.getStatus() != MediaAssetStatus.ACTIVE || asset.getVisibility() != MediaVisibility.PUBLIC) {
            throw new MediaAssetNotFoundException(assetId);
        }

        int currentVersionNumber = asset.getCurrentVersionNumber();
        MediaAssetVersion currentVersion = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(assetId, currentVersionNumber)
                .orElseThrow(() -> new MediaAssetVersionNotFoundException(assetId, currentVersionNumber));

        if (!binaryStoragePort.providerId().equals(currentVersion.getStorageLocation().providerId())) {
            throw new StorageException(
                    "Storage provider mismatch for asset " + assetId
                            + ": configured provider is " + binaryStoragePort.providerId().value()
                            + ", but asset requires " + currentVersion.getStorageLocation().providerId().value()
            );
        }

        InputStream contentStream = binaryStoragePort.open(currentVersion.getStorageLocation().key());

        return new GetMediaAssetContentResult(
                contentStream,
                currentVersion.getSizeBytes(),
                currentVersion.getMimeType().value(),
                currentVersion.getContentHash().value()
        );
    }
}
