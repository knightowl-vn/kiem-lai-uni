package com.universe.media.application.ports.storage;

import java.util.Objects;

/**
 * Metadata of a part verified as uploaded to the remote storage provider.
 */
public record MultipartUploadedPart(int partNumber, long sizeBytes, String eTag) {
    public MultipartUploadedPart {
        if (partNumber < 1 || partNumber > 10_000) {
            throw new IllegalArgumentException("partNumber must be between 1 and 10000, got: " + partNumber);
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes cannot be negative: " + sizeBytes);
        }
        Objects.requireNonNull(eTag, "eTag cannot be null.");
        if (eTag.isBlank()) {
            throw new IllegalArgumentException("eTag cannot be blank.");
        }
    }
}
