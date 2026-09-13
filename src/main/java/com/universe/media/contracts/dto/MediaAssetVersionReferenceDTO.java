package com.universe.media.contracts.dto;

import java.util.UUID;

/**
 * Public consumer-neutral DTO identifying an exact immutable MediaAssetVersion.
 */
public record MediaAssetVersionReferenceDTO(
        UUID assetId,
        int versionNumber,
        String contentHash
) {
}
