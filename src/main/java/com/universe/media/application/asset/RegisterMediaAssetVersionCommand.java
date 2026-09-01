package com.universe.media.application.asset;

import java.util.UUID;

public record RegisterMediaAssetVersionCommand(
        UUID assetId,
        String storageProviderId,
        String storageKey,
        String publicUrl,
        String contentHash,
        String mimeType,
        long sizeBytes,
        String originalFilename
) {
}
