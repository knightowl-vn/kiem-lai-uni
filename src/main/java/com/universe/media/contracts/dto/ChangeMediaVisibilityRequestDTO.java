package com.universe.media.contracts.dto;

import java.util.UUID;

/**
 * Public request DTO for changing the visibility of a media asset.
 */
public record ChangeMediaVisibilityRequestDTO(
        UUID assetId,
        MediaVisibilityDTO newVisibility
) {
}
