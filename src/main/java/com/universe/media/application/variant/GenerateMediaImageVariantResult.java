package com.universe.media.application.variant;

import java.time.Instant;
import java.util.UUID;

/**
 * Passive application result for a generated image variant.
 */
public record GenerateMediaImageVariantResult(
        UUID variantId,
        UUID assetId,
        UUID versionId,
        int versionNumber,
        String variantKey,
        int targetWidth,
        String publicUrl,
        String mimeType,
        Long sizeBytes,
        Integer width,
        Integer height,
        Instant createdAt
) {
    public GenerateMediaImageVariantResult(
            UUID variantId,
            UUID assetId,
            UUID versionId,
            int versionNumber,
            String variantKey,
            int targetWidth,
            String mimeType,
            long sizeBytes,
            int width,
            int height,
            Instant createdAt
    ) {
        this(variantId, assetId, versionId, versionNumber, variantKey, targetWidth, null, mimeType, sizeBytes, width, height, createdAt);
    }

    public boolean isVirtual() {
        return publicUrl != null;
    }

    public boolean isPhysical() {
        return publicUrl == null;
    }
}
