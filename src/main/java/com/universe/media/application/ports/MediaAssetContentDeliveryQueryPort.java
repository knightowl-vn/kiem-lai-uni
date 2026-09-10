package com.universe.media.application.ports;

import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVisibility;

import java.util.Optional;
import java.util.UUID;

/**
 * Media-internal read model for resolving the declared current binary used by public delivery.
 */
public interface MediaAssetContentDeliveryQueryPort {

    Optional<MediaAssetContentDeliverySnapshot> findByAssetId(UUID assetId);

    record MediaAssetContentDeliverySnapshot(
            UUID assetId,
            MediaAssetStatus status,
            MediaVisibility visibility,
            int currentVersionNumber,
            UUID versionId,
            UUID versionAssetId,
            Integer versionNumber,
            String storageProviderId,
            String storageKey,
            String contentHash,
            String mimeType,
            Long sizeBytes
    ) {
    }
}
