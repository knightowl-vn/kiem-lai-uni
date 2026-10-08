package com.universe.media.application.ports.storage;

import com.universe.media.domain.StorageLocation;

import java.util.Objects;

/**
 * Result of completing a multipart upload.
 */
public record MultipartCompletedObject(
        StorageLocation location,
        String eTag
) {
    public MultipartCompletedObject {
        Objects.requireNonNull(location, "StorageLocation cannot be null.");
        Objects.requireNonNull(eTag, "eTag cannot be null.");
        if (eTag.isBlank()) {
            throw new IllegalArgumentException("eTag cannot be blank.");
        }
    }
}
