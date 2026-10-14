package com.universe.media.contracts.dto;

import java.util.Objects;

/**
 * Submitted part evidence in a multipart upload completion manifest.
 */
public record MultipartManifestPartDTO(
        int partNumber,
        long sizeBytes,
        String eTag
) {
    public MultipartManifestPartDTO {
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
}
