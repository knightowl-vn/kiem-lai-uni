package com.universe.media.application.variant;

import com.universe.media.application.asset.GetMediaAssetContentResult;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.MediaImageVariantNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaImageVariant;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case for retrieving the binary content stream of a persisted image variant
 * for the CURRENT version of an ACTIVE and PUBLIC media asset.
 * <p>
 * <strong>Stream Ownership:</strong> The caller/delivery layer owns the returned {@link InputStream}
 * and is responsible for properly closing it after streaming or handling the response.
 */
@Service
public class GetMediaImageVariantContentUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort;
    private final BinaryStoragePort binaryStoragePort;

    public GetMediaImageVariantContentUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort,
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
        this.mediaImageVariantRepositoryPort = Objects.requireNonNull(
                mediaImageVariantRepositoryPort,
                "MediaImageVariantRepositoryPort cannot be null."
        );
        this.binaryStoragePort = Objects.requireNonNull(
                binaryStoragePort,
                "BinaryStoragePort cannot be null."
        );
    }

    @Transactional(readOnly = true)
    public GetMediaAssetContentResult execute(
            GetMediaImageVariantContentQuery query
    ) {
        Objects.requireNonNull(query, "GetMediaImageVariantContentQuery cannot be null.");

        UUID assetId = Objects.requireNonNull(query.assetId(), "Asset ID cannot be null.");
        String variantKey = Objects.requireNonNull(query.variantKey(), "Variant key cannot be null.");

        // 1. Resolve asset and gate on type, status, and visibility
        MediaAsset asset = mediaAssetRepositoryPort.findById(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (asset.getMediaType() != MediaType.IMAGE
                || asset.getStatus() != MediaAssetStatus.ACTIVE
                || asset.getVisibility() != MediaVisibility.PUBLIC) {
            throw new MediaAssetNotFoundException(assetId);
        }

        // 2. Resolve exact current version
        int currentVersionNumber = asset.getCurrentVersionNumber();
        MediaAssetVersion currentVersion = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(assetId, currentVersionNumber)
                .orElseThrow(() -> new MediaAssetVersionNotFoundException(assetId, currentVersionNumber));

        // 3. Resolve exact variant for that current version (exact key matching, no normalization)
        MediaImageVariant variant = mediaImageVariantRepositoryPort
                .findByVersionIdAndVariantKey(currentVersion.getId(), variantKey)
                .orElseThrow(() -> new MediaImageVariantNotFoundException(assetId, currentVersionNumber, variantKey));

        // 4. Validate storage provider
        if (!binaryStoragePort.providerId().equals(variant.getStorageLocation().providerId())) {
            throw new StorageException(
                    "Storage provider mismatch for variant " + variantKey + " of asset " + assetId
                            + ": configured provider is " + binaryStoragePort.providerId().value()
                            + ", but variant requires " + variant.getStorageLocation().providerId().value()
            );
        }

        // 5. Open binary stream using the variant's StorageKey
        InputStream contentStream = binaryStoragePort.open(variant.getStorageLocation().key());

        return new GetMediaAssetContentResult(
                contentStream,
                variant.getSizeBytes(),
                variant.getMimeType().value(),
                variant.getContentHash().value()
        );
    }
}
