package com.universe.media.application.asset;

import java.time.Instant;
import java.util.UUID;

public record MediaVersionItemResult(
        UUID id,
        UUID assetId,
        int versionNumber,
        String storageProviderId,
        String storageKey,
        String publicUrl,
        String contentHash,
        String mimeType,
        long sizeBytes,
        String originalFilename,
        Instant createdAt
) {
}
