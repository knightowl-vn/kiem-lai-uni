package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.contracts.dto.MediaAssetStreamDTO;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal use case for streaming media asset binary content directly to authorized consumers.
 *
 * <p><strong>Stream Ownership:</strong> The caller owns and must close the returned {@link InputStream}.
 */
@Service
public class StreamMediaAssetUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final StorageProviderResolverPort storageProviderResolverPort;

    public StreamMediaAssetUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            StorageProviderResolverPort storageProviderResolverPort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort, "MediaAssetRepositoryPort cannot be null."
        );
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(
                mediaAssetVersionRepositoryPort, "MediaAssetVersionRepositoryPort cannot be null."
        );
        this.storageProviderResolverPort = Objects.requireNonNull(
                storageProviderResolverPort, "StorageProviderResolverPort cannot be null."
        );
    }

    @Transactional(readOnly = true)
    public MediaAssetStreamDTO openStream(UUID assetId) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        MediaAsset asset = mediaAssetRepositoryPort.findById(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));
        if (asset.getStatus() != MediaAssetStatus.ACTIVE) {
            throw new MediaAssetNotFoundException(assetId);
        }

        MediaAssetVersion version = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(assetId, asset.getCurrentVersionNumber())
                .orElseThrow(() -> new MediaAssetVersionNotFoundException(assetId, asset.getCurrentVersionNumber()));

        BinaryStoragePort port = storageProviderResolverPort.resolve(version.getStorageLocation().providerId());
        InputStream stream = port.open(version.getStorageLocation().key());

        return new MediaAssetStreamDTO(
                asset.getId(),
                version.getVersionNumber(),
                version.getMimeType().value(),
                version.getSizeBytes(),
                version.getContentHash().value(),
                stream
        );
    }

    @Transactional(readOnly = true)
    public MediaAssetStreamDTO openRangeStream(UUID assetId, long startInclusive, long length) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        if (startInclusive < 0) {
            throw new IllegalArgumentException("startInclusive cannot be negative: " + startInclusive);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }

        MediaAsset asset = mediaAssetRepositoryPort.findById(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));
        if (asset.getStatus() != MediaAssetStatus.ACTIVE) {
            throw new MediaAssetNotFoundException(assetId);
        }

        MediaAssetVersion version = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(assetId, asset.getCurrentVersionNumber())
                .orElseThrow(() -> new MediaAssetVersionNotFoundException(assetId, asset.getCurrentVersionNumber()));

        long totalSize = version.getSizeBytes();
        if (startInclusive >= totalSize || length > totalSize - startInclusive) {
            throw new IllegalArgumentException(
                    String.format("Requested range [start: %d, length: %d) exceeds version size %d.",
                            startInclusive, length, totalSize)
            );
        }

        BinaryStoragePort port = storageProviderResolverPort.resolve(version.getStorageLocation().providerId());
        InputStream stream = port.openRange(version.getStorageLocation().key(), startInclusive, length);

        return new MediaAssetStreamDTO(
                asset.getId(),
                version.getVersionNumber(),
                version.getMimeType().value(),
                version.getSizeBytes(),
                version.getContentHash().value(),
                stream
        );
    }
}
