package com.universe.media.application.ports;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight candidate projection returned by the MediaAsset persistence port.
 */
public record MediaAssetCandidate(
        UUID assetId,
        Instant createdAt
) {
    public MediaAssetCandidate {
        Objects.requireNonNull(
                assetId,
                "Asset ID cannot be null."
        );
        Objects.requireNonNull(
                createdAt,
                "Created at timestamp cannot be null."
        );
    }
}
