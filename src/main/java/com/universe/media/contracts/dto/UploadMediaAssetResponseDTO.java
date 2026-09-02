package com.universe.media.contracts.dto;

import java.util.UUID;

/**
 * Public response DTO returned after successfully uploading a new media asset.
 */
public record UploadMediaAssetResponseDTO(
        UUID assetId
) {
}
