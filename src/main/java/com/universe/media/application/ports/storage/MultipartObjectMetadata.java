package com.universe.media.application.ports.storage;

import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;

import java.util.Map;
import java.util.Objects;

/**
 * Metadata inspected from an existing object in remote storage (e.g. via HeadObject).
 */
public record MultipartObjectMetadata(
        StorageKey key,
        long contentLength,
        String contentType,
        String eTag,
        Map<String, String> userMetadata,
        String versionId,
        StorageProviderId providerId,
        String bucketName
) {
    public MultipartObjectMetadata {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength cannot be negative: " + contentLength);
        }
        userMetadata = (userMetadata != null) ? Map.copyOf(userMetadata) : Map.of();
    }

    public MultipartObjectMetadata(
            StorageKey key,
            long contentLength,
            String contentType,
            String eTag,
            Map<String, String> userMetadata,
            String versionId
    ) {
        this(key, contentLength, contentType, eTag, userMetadata, versionId, null, null);
    }

    public MultipartObjectMetadata(
            StorageKey key,
            long contentLength,
            String contentType,
            String eTag
    ) {
        this(key, contentLength, contentType, eTag, Map.of(), null, null, null);
    }
}
