package com.universe.media.application.ports.storage;

import java.util.Objects;

/**
 * Immutable command instructing {@link HlsPackageArtifactStoragePort} to upload and remotely verify
 * a locally packaged and locally verified HLS package under its immutable prefix.
 *
 * <p>Uses a lifecycle-bound {@link HlsArtifactStreamSource} rather than a detached filesystem path
 * so active uploads remain pinned while preventing cleanup deletion under active readers.
 */
public record HlsPackageUploadCommand(
        String bucketName,
        HlsExpectedArtifactManifest expectedManifest,
        HlsArtifactStreamSource artifactSource
) {

    public HlsPackageUploadCommand {
        bucketName = HlsPackageKeyValidator.validateBucketName(bucketName);
        Objects.requireNonNull(expectedManifest, "expectedManifest cannot be null.");
        Objects.requireNonNull(artifactSource, "artifactSource cannot be null.");
    }
}
