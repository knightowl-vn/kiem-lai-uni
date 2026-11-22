package com.universe.media.application.ports;

import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight projection for bounded, lock-free HLS package purge candidate discovery (MS-08 HLS-2B3A2B).
 */
public record MediaVideoHlsPackagePurgeCandidate(
        UUID packageId,
        UUID assetId
) {
    public MediaVideoHlsPackagePurgeCandidate {
        Objects.requireNonNull(packageId, "packageId cannot be null.");
        Objects.requireNonNull(assetId, "assetId cannot be null.");
    }
}
