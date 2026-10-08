package com.universe.media.application.ports.storage;

import java.util.Objects;

/**
 * Identifier and ETag for an uploaded part required when completing a multipart upload.
 */
public record MultipartPartETag(int partNumber, String eTag) {
    public MultipartPartETag {
        if (partNumber < 1 || partNumber > 10_000) {
            throw new IllegalArgumentException("partNumber must be between 1 and 10000, got: " + partNumber);
        }
        Objects.requireNonNull(eTag, "eTag cannot be null.");
        if (eTag.isBlank()) {
            throw new IllegalArgumentException("eTag cannot be blank.");
        }
    }
}
