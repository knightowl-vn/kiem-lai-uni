package com.universe.media.application.variant;

import java.util.Objects;
import java.util.UUID;

/**
 * Query to retrieve binary content of a persisted image variant for the current version of an asset.
 */
public record GetMediaImageVariantContentQuery(
        UUID assetId,
        String variantKey
) {
    public GetMediaImageVariantContentQuery {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        Objects.requireNonNull(variantKey, "Variant key cannot be null.");
    }
}
