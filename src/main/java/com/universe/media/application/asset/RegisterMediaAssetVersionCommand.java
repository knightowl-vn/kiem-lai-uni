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
        String originalFilename,
        UUID expectedAllocationId,
        UUID reservationCorrelationId
) {
    public RegisterMediaAssetVersionCommand(
            UUID assetId,
            String storageProviderId,
            String storageKey,
            String publicUrl,
            String contentHash,
            String mimeType,
            long sizeBytes,
            String originalFilename,
            UUID expectedAllocationId
    ) {
        this(assetId, storageProviderId, storageKey, publicUrl, contentHash, mimeType, sizeBytes, originalFilename, expectedAllocationId, null);
    }

    public RegisterMediaAssetVersionCommand(
            UUID assetId,
            String storageProviderId,
            String storageKey,
            String publicUrl,
            String contentHash,
            String mimeType,
            long sizeBytes,
            String originalFilename
    ) {
        this(assetId, storageProviderId, storageKey, publicUrl, contentHash, mimeType, sizeBytes, originalFilename, null, null);
    }
}
