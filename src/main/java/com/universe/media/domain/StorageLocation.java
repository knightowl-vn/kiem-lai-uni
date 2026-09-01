package com.universe.media.domain;

import java.util.Objects;

/**
 * Value Object representing the complete storage location of a binary asset.
 *
 * Combines an opaque StorageProviderId and a StorageKey.
 */
public record StorageLocation(
        StorageProviderId providerId,
        StorageKey key
) {

    public StorageLocation {
        Objects.requireNonNull(
                providerId,
                "StorageProviderId cannot be null."
        );

        Objects.requireNonNull(
                key,
                "StorageKey cannot be null."
        );
    }

    public static StorageLocation of(
            String providerId,
            String key
    ) {
        return new StorageLocation(
                StorageProviderId.of(providerId),
                StorageKey.of(key)
        );
    }

    public static StorageLocation of(
            StorageProviderId providerId,
            StorageKey key
    ) {
        return new StorageLocation(
                providerId,
                key
        );
    }
}
