package com.universe.media.contracts.dto;

import java.util.UUID;

/**
 * Public response DTO returned after successfully uploading a new version of a media asset.
 */
public record UploadMediaAssetVersionResponseDTO(
        UUID assetId,
        int versionNumber
) {
}
