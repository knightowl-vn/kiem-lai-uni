package com.universe.media.application.ports;

import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVisibility;

import java.util.Optional;
import java.util.UUID;

/**
 * Read model for an asset and the existence of its declared current version.
 */
public interface MediaAssetCurrentMetadataQueryPort {

    Optional<MediaAssetCurrentMetadataSnapshot> findByAssetId(UUID assetId);

    record MediaAssetCurrentMetadataSnapshot(
            UUID assetId,
            MediaAssetStatus status,
            MediaVisibility visibility,
            int currentVersionNumber,
            UUID declaredCurrentVersionAssetId,
            Integer declaredCurrentVersionNumber
    ) {
    }
}
