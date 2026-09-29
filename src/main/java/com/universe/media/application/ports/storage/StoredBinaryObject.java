package com.universe.media.application.ports.storage;

import com.universe.media.domain.StorageLocation;

import java.util.Objects;

/**
 * Value object representing the result of storing a binary object in a storage provider.
 *
 * @param location  the canonical storage location (providerId + storageKey)
 * @param publicUrl optional direct public/CDN URL (e.g. for Cloudinary assets; null for local/r2)
 */
public record StoredBinaryObject(
        StorageLocation location,
        String publicUrl
) {

    public StoredBinaryObject {
        Objects.requireNonNull(location, "StorageLocation cannot be null.");
    }

    public static StoredBinaryObject of(StorageLocation location) {
        return new StoredBinaryObject(location, null);
    }

    public static StoredBinaryObject of(StorageLocation location, String publicUrl) {
        return new StoredBinaryObject(location, publicUrl);
    }
}
