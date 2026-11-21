package com.universe.media.application.hls;

import com.universe.media.domain.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable verification evidence produced by the HLS artifact verifier (HLS-2)
 * before a packaging attempt may transition to READY.
 */
public record VerifiedHlsPackageReceipt(
        ContentHash masterManifestSha256,
        long totalSizeBytes,
        int objectCount,
        String renditionsJson,
        Instant verifiedAt
) {

    public VerifiedHlsPackageReceipt {
        Objects.requireNonNull(masterManifestSha256, "masterManifestSha256 cannot be null.");
        if (totalSizeBytes <= 0) {
            throw new IllegalArgumentException("totalSizeBytes must be > 0: " + totalSizeBytes);
        }
        if (objectCount < 4) {
            throw new IllegalArgumentException("objectCount must be >= 4: " + objectCount);
        }
        Objects.requireNonNull(verifiedAt, "verifiedAt cannot be null.");
    }
}
