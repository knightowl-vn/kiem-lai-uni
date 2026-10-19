package com.universe.screen.application.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Screen-owned locked inspection result for a MediaAsset.
 */
public record ScreenMediaAssetInspectionDTO(
        UUID assetId,
        String mediaType,
        String status
) {
    public ScreenMediaAssetInspectionDTO {
        Objects.requireNonNull(assetId, "assetId cannot be null.");
        Objects.requireNonNull(mediaType, "mediaType cannot be null.");
        Objects.requireNonNull(status, "status cannot be null.");
    }
}
