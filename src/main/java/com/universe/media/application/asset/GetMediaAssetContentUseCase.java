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
import com.universe.media.domain.StorageKey;

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

        ResolvedContent resolvedContent = resolveEligibleCurrentContent(query.assetId());
        InputStream contentStream = binaryStoragePort.open(resolvedContent.storageKey());

        return new GetMediaAssetContentResult(
                contentStream,
                resolvedContent.metadata().sizeBytes(),
                resolvedContent.metadata().mimeType(),
                resolvedContent.metadata().contentHash()
        );
    }

    /**
     * Resolves delivery metadata without opening the physical binary.
     */
    @Transactional(readOnly = true)
    public GetMediaAssetContentMetadataResult resolveMetadata(
            GetMediaAssetContentQuery query
    ) {
        Objects.requireNonNull(query, "GetMediaAssetContentQuery cannot be null.");
        return resolveEligibleCurrentContent(query.assetId()).metadata();
    }

    /**
     * Revalidates and opens the exact current version represented by previously resolved metadata.
     * The caller owns and must close the returned stream.
     */
    @Transactional(readOnly = true)
    public InputStream open(
            GetMediaAssetContentMetadataResult metadata
    ) {
        ResolvedContent resolvedContent = resolveMatchingCurrentContent(metadata);
        return binaryStoragePort.open(resolvedContent.storageKey());
    }

    /**
     * Revalidates and opens a provider-native bounded range of the exact current version.
     * The caller owns and must close the returned stream.
     */
    @Transactional(readOnly = true)
    public InputStream openRange(
            GetMediaAssetContentMetadataResult metadata,
            long startInclusive,
            long length
    ) {
        if (startInclusive < 0) {
            throw new IllegalArgumentException("startInclusive cannot be negative: " + startInclusive);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }
        ResolvedContent resolvedContent = resolveMatchingCurrentContent(metadata);
        return binaryStoragePort.openRange(resolvedContent.storageKey(), startInclusive, length);
    }

    private ResolvedContent resolveEligibleCurrentContent(UUID assetId) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");

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

        GetMediaAssetContentMetadataResult metadata = new GetMediaAssetContentMetadataResult(
                assetId,
                currentVersion.getVersionNumber(),
                currentVersion.getSizeBytes(),
                currentVersion.getMimeType().value(),
                currentVersion.getContentHash().value()
        );

        return new ResolvedContent(metadata, currentVersion.getStorageLocation().key());
    }

    private ResolvedContent resolveMatchingCurrentContent(
            GetMediaAssetContentMetadataResult metadata
    ) {
        Objects.requireNonNull(metadata, "Content metadata cannot be null.");
        ResolvedContent resolvedContent = resolveEligibleCurrentContent(metadata.assetId());
        if (!resolvedContent.metadata().equals(metadata)) {
            throw new StorageException(
                    "Current Media content changed after delivery metadata was resolved for asset "
                            + metadata.assetId()
            );
        }
        return resolvedContent;
    }

    private record ResolvedContent(
            GetMediaAssetContentMetadataResult metadata,
            StorageKey storageKey
    ) {
    }
}
