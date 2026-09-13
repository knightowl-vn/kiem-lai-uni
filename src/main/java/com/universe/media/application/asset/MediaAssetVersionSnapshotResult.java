package com.universe.media.application.asset;

import java.util.UUID;

public record MediaAssetVersionSnapshotResult(
        UUID assetId,
        int versionNumber,
        String contentHash,
        String mimeType,
        long sizeBytes,
        String originalFilename
) {
}
