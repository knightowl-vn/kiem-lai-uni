package com.universe.media.application.asset;

import java.io.InputStream;
import java.util.UUID;

public record MediaAssetVersionContentResult(
        UUID assetId,
        int versionNumber,
        String contentHash,
        String mimeType,
        long sizeBytes,
        InputStream content
) {
}
