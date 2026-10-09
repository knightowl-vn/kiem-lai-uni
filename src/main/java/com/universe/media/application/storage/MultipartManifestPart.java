package com.universe.media.application.storage;

import com.universe.media.application.ports.storage.MultipartUploadedPart;

import java.util.Objects;

/**
 * Submitted part entry in a multipart completion manifest.
 */
public record MultipartManifestPart(int partNumber, long sizeBytes, String eTag) {

    public MultipartManifestPart {
        if (partNumber < 1 || partNumber > 10_000) {
            throw new IllegalArgumentException("partNumber must be between 1 and 10000, got: " + partNumber);
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be strictly positive, got: " + sizeBytes);
        }
        Objects.requireNonNull(eTag, "eTag cannot be null.");
        if (eTag.isBlank()) {
            throw new IllegalArgumentException("eTag cannot be blank.");
        }
    }

    public static MultipartManifestPart from(MultipartUploadedPart uploadedPart) {
        Objects.requireNonNull(uploadedPart, "MultipartUploadedPart cannot be null.");
        return new MultipartManifestPart(
                uploadedPart.partNumber(),
                uploadedPart.sizeBytes(),
                uploadedPart.eTag()
        );
    }
}
