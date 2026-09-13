package com.universe.media.contracts.dto;

import java.util.UUID;

/**
 * Consumer-neutral request DTO to generate an image variant for a media asset.
 *
 * @param mediaAssetId ID of the target media asset
 * @param targetWidth  target bounding width in pixels
 */
public record GenerateImageVariantRequestDTO(
        UUID mediaAssetId,
        int targetWidth
) {
}
