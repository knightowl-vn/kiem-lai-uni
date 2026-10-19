package com.universe.media.contracts.dto;

import java.util.UUID;

/**
 * Public contract DTO representing the locked inspection state of a media asset.
 *
 * <p>Provides authoritative lifecycle status and metadata observed under pessimistic row lock.
 */
public record MediaAssetInspectionDTO(
        UUID id,
        MediaTypeDTO mediaType,
        MediaVisibilityDTO visibility,
        MediaAssetStatusDTO status,
        int currentVersionNumber
) {
}
