package com.universe.media.contracts.dto;

import java.util.UUID;

/**
 * Consumer-neutral metadata for an asset whose declared current version has been resolved.
 */
public record MediaAssetCurrentMetadataDTO(
        UUID id,
        MediaAssetStatusDTO status,
        MediaVisibilityDTO visibility,
        int currentVersionNumber
) {
}
