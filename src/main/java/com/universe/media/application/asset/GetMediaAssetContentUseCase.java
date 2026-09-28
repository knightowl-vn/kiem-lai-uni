package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaAssetContentDeliveryQueryPort;
import com.universe.media.application.ports.MediaAssetContentDeliveryQueryPort.MediaAssetContentDeliverySnapshot;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case for retrieving the delivery content or redirect plan of an ACTIVE and PUBLIC media asset.
 * <p>
 * <strong>Stream Ownership:</strong> When streaming content is returned, the caller/delivery layer is responsible
 * for closing the {@link InputStream} contained within {@link GetMediaAssetContentResult}.
 */
@Service
public class GetMediaAssetContentUseCase {

    private final MediaAssetContentDeliveryQueryPort contentDeliveryQueryPort;
    private final StorageProviderResolverPort storageProviderResolverPort;

    public GetMediaAssetContentUseCase(
            MediaAssetContentDeliveryQueryPort contentDeliveryQueryPort,
            StorageProviderResolverPort storageProviderResolverPort
    ) {
        this.contentDeliveryQueryPort = Objects.requireNonNull(
                contentDeliveryQueryPort,
                "MediaAssetContentDeliveryQueryPort cannot be null."
        );
        this.storageProviderResolverPort = Objects.requireNonNull(
                storageProviderResolverPort,
                "StorageProviderResolverPort cannot be null."
        );
    }

    @Transactional(readOnly = true)
    public GetMediaAssetContentResult execute(
            GetMediaAssetContentQuery query
    ) {
        Objects.requireNonNull(query, "GetMediaAssetContentQuery cannot be null.");

        ResolvedContent resolvedContent = resolveEligibleCurrentContent(query.assetId());

        if (resolvedContent.metadata().isRedirect()) {
            return GetMediaAssetContentResult.redirect(
                    resolvedContent.metadata().publicUrl(),
                    resolvedContent.metadata().sizeBytes(),
                    resolvedContent.metadata().mimeType(),
                    resolvedContent.metadata().contentHash()
            );
        }

        BinaryStoragePort port = storageProviderResolverPort.resolve(resolvedContent.storageProviderId());
        InputStream contentStream = port.open(resolvedContent.storageKey());

        return GetMediaAssetContentResult.stream(
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
        BinaryStoragePort port = storageProviderResolverPort.resolve(resolvedContent.storageProviderId());
        return port.open(resolvedContent.storageKey());
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
        BinaryStoragePort port = storageProviderResolverPort.resolve(resolvedContent.storageProviderId());
        return port.openRange(resolvedContent.storageKey(), startInclusive, length);
    }

    private ResolvedContent resolveEligibleCurrentContent(UUID assetId) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");

        MediaAssetContentDeliverySnapshot snapshot = contentDeliveryQueryPort.findByAssetId(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (snapshot.status() != MediaAssetStatus.ACTIVE
                || snapshot.visibility() != MediaVisibility.PUBLIC) {
            throw new MediaAssetNotFoundException(assetId);
        }

        int currentVersionNumber = snapshot.currentVersionNumber();
        if (!assetId.equals(snapshot.assetId())
                || snapshot.versionId() == null
                || !assetId.equals(snapshot.versionAssetId())
                || snapshot.versionNumber() == null
                || snapshot.versionNumber() != currentVersionNumber) {
            throw new MediaAssetVersionNotFoundException(assetId, currentVersionNumber);
        }

        ResolvedTechnicalMetadata technicalMetadata = resolveTechnicalMetadata(snapshot, assetId);

        String publicUrl = snapshot.publicUrl();
        if (publicUrl != null && !publicUrl.isBlank()) {
            GetMediaAssetContentMetadataResult metadata = new GetMediaAssetContentMetadataResult(
                    assetId,
                    currentVersionNumber,
                    technicalMetadata.sizeBytes(),
                    technicalMetadata.mimeType().value(),
                    technicalMetadata.contentHash().value(),
                    publicUrl.trim()
            );
            return new ResolvedContent(metadata, technicalMetadata.storageKey(), technicalMetadata.storageProviderId());
        }

        // Validate that provider exists for streaming reads
        storageProviderResolverPort.resolve(technicalMetadata.storageProviderId());

        GetMediaAssetContentMetadataResult metadata = new GetMediaAssetContentMetadataResult(
                assetId,
                currentVersionNumber,
                technicalMetadata.sizeBytes(),
                technicalMetadata.mimeType().value(),
                technicalMetadata.contentHash().value(),
                null
        );

        return new ResolvedContent(metadata, technicalMetadata.storageKey(), technicalMetadata.storageProviderId());
    }

    private ResolvedTechnicalMetadata resolveTechnicalMetadata(
            MediaAssetContentDeliverySnapshot snapshot,
            UUID assetId
    ) {
        if (snapshot.storageProviderId() == null
                || snapshot.storageKey() == null
                || snapshot.contentHash() == null
                || snapshot.mimeType() == null
                || snapshot.sizeBytes() == null
                || snapshot.sizeBytes() <= 0) {
            throw new StorageException("Invalid current Media delivery metadata for asset " + assetId);
        }
        try {
            return new ResolvedTechnicalMetadata(
                    StorageProviderId.of(snapshot.storageProviderId()),
                    StorageKey.of(snapshot.storageKey()),
                    ContentHash.of(snapshot.contentHash()),
                    MimeType.of(snapshot.mimeType()),
                    snapshot.sizeBytes()
            );
        } catch (IllegalArgumentException e) {
            throw new StorageException("Invalid current Media delivery metadata for asset " + assetId, e);
        }
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
            StorageKey storageKey,
            StorageProviderId storageProviderId
    ) {
    }

    private record ResolvedTechnicalMetadata(
            StorageProviderId storageProviderId,
            StorageKey storageKey,
            ContentHash contentHash,
            MimeType mimeType,
            long sizeBytes
    ) {
    }
}
