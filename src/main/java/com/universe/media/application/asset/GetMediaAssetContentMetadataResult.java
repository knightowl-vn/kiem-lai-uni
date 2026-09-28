package com.universe.media.application.asset;

import java.net.URI;
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
        String contentHash,
        String publicUrl
) {

    public GetMediaAssetContentMetadataResult(
            UUID assetId,
            int versionNumber,
            long sizeBytes,
            String mimeType,
            String contentHash
    ) {
        this(assetId, versionNumber, sizeBytes, mimeType, contentHash, null);
    }

    public boolean isRedirect() {
        return publicUrl != null && !publicUrl.isBlank();
    }

    public URI redirectUri() {
        return isRedirect() ? GetMediaAssetContentResult.validateHttpsUri(publicUrl) : null;
    }
}
