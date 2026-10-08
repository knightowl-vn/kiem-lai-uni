package com.universe.media.application.ports.storage;

import com.universe.media.domain.StorageKey;

import java.util.Objects;

/**
 * Metadata inspected from an existing object in remote storage (e.g. via HeadObject).
 */
public record MultipartObjectMetadata(
        StorageKey key,
        long contentLength,
        String contentType,
        String eTag
) {
    public MultipartObjectMetadata {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength cannot be negative: " + contentLength);
        }
    }
}
