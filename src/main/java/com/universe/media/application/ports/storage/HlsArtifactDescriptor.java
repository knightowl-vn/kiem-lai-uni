package com.universe.media.application.ports.storage;

import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;

import java.util.Objects;

/**
 * Immutable metadata descriptor for a single locally verified HLS artifact inside a package attempt.
 */
public record HlsArtifactDescriptor(
        String relativePath,
        StorageKey objectKey,
        long sizeBytes,
        ContentHash sha256,
        HlsArtifactType artifactType,
        MimeType contentType,
        HlsUploadStage uploadStage
) {

    public HlsArtifactDescriptor {
        HlsPackageKeyValidator.ValidatedArtifactClassification expected =
                HlsPackageKeyValidator.validateAndClassifyRelativePath(relativePath);
        Objects.requireNonNull(objectKey, "objectKey cannot be null.");
        HlsPackageKeyValidator.validateStandaloneObjectKey(objectKey, expected.relativePath());
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("HLS artifact sizeBytes must be > 0 for " + relativePath + ", got: " + sizeBytes);
        }
        Objects.requireNonNull(sha256, "sha256 cannot be null.");
        if (expected.artifactType() != artifactType) {
            throw new IllegalArgumentException(
                    "Mismatched artifactType for " + relativePath + ": expected " + expected.artifactType() + ", got " + artifactType
            );
        }
        if (!expected.contentType().equals(contentType)) {
            throw new IllegalArgumentException(
                    "Mismatched contentType for " + relativePath + ": expected " + expected.contentType() + ", got " + contentType
            );
        }
        if (expected.uploadStage() != uploadStage) {
            throw new IllegalArgumentException(
                    "Mismatched uploadStage for " + relativePath + ": expected " + expected.uploadStage() + ", got " + uploadStage
            );
        }
    }
}
