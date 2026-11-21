package com.universe.media.application.ports.storage;

import java.util.Objects;

/**
 * Immutable command for independent remote verification of an uploaded HLS package.
 */
public record HlsRemotePackageVerificationCommand(
        String bucketName,
        HlsExpectedArtifactManifest expectedManifest,
        boolean digestAllSegmentsViaGet
) {

    public HlsRemotePackageVerificationCommand {
        bucketName = HlsPackageKeyValidator.validateBucketName(bucketName);
        Objects.requireNonNull(expectedManifest, "expectedManifest cannot be null.");
    }
}
