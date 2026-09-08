package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionContentHashMismatchException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * Opens the binary content for an exact immutable MediaAssetVersion reference.
 *
 * <p><strong>Stream Ownership:</strong> The caller owns and must close the returned {@link InputStream}.
 */
@Service
public class OpenMediaAssetVersionContentUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final BinaryStoragePort binaryStoragePort;

    public OpenMediaAssetVersionContentUseCase(
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
    public MediaAssetVersionContentResult execute(
            OpenMediaAssetVersionContentQuery query
    ) {
        Objects.requireNonNull(query, "OpenMediaAssetVersionContentQuery cannot be null.");
        UUID assetId = Objects.requireNonNull(query.assetId(), "Asset ID cannot be null.");
        int versionNumber = query.versionNumber();
        ContentHash expectedContentHash = ContentHash.of(query.contentHash());

        MediaAsset asset = mediaAssetRepositoryPort.findById(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));
        GetCurrentMediaAssetVersionSnapshotUseCase.requireInternalConsumerEligible(asset);

        MediaAssetVersion version = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(assetId, versionNumber)
                .orElseThrow(() -> new MediaAssetVersionNotFoundException(assetId, versionNumber));

        if (!version.getContentHash().equals(expectedContentHash)) {
            throw new MediaAssetVersionContentHashMismatchException(assetId, versionNumber);
        }

        if (!binaryStoragePort.providerId().equals(version.getStorageLocation().providerId())) {
            throw new StorageException(
                    "Storage provider mismatch for asset " + assetId
                            + ": configured provider is " + binaryStoragePort.providerId().value()
                            + ", but asset requires " + version.getStorageLocation().providerId().value()
            );
        }

        InputStream contentStream = binaryStoragePort.open(version.getStorageLocation().key());

        return new MediaAssetVersionContentResult(
                version.getAssetId(),
                version.getVersionNumber(),
                version.getContentHash().value(),
                version.getMimeType().value(),
                version.getSizeBytes(),
                contentStream
        );
    }
}
