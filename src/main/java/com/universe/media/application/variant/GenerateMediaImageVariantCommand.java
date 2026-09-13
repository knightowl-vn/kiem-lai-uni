package com.universe.media.application.variant;

import com.universe.media.domain.ImageVariantSpec;

import java.util.UUID;

/**
 * Command to request synchronous generation of an image variant for an asset version.
 */
public record GenerateMediaImageVariantCommand(
        UUID assetId,
        Integer versionNumber,
        ImageVariantSpec spec
) {
    public static GenerateMediaImageVariantCommand of(
            UUID assetId,
            ImageVariantSpec spec
    ) {
        return new GenerateMediaImageVariantCommand(assetId, null, spec);
    }

    public static GenerateMediaImageVariantCommand of(
            UUID assetId,
            int versionNumber,
            ImageVariantSpec spec
    ) {
        return new GenerateMediaImageVariantCommand(assetId, versionNumber, spec);
    }
}
