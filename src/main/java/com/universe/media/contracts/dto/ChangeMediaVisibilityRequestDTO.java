package com.universe.media.contracts.dto;

import com.universe.media.domain.MediaVisibility;

import java.util.UUID;

/**
 * Public request DTO for changing the visibility of a media asset.
 */
public record ChangeMediaVisibilityRequestDTO(
        UUID assetId,
        MediaVisibility newVisibility
) {
}
