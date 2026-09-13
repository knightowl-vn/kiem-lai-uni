package com.universe.media.application.asset;

import java.util.UUID;

/**
 * Public-delivery metadata for the current immutable version of an eligible Media asset.
 *
 * <p>Storage provider and storage-key details intentionally remain inside the Media application boundary.
 */
public record GetMediaAssetContentMetadataResult(
        UUID assetId,
        int versionNumber,
        long sizeBytes,
        String mimeType,
        String contentHash
) {
}
