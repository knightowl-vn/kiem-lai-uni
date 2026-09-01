package com.universe.media.domain;

import java.util.Objects;

/**
 * Opaque unique key or identifier of a binary asset within its storage provider.
 *
 * Preserves the opaque identifier exactly as provided without filesystem or path-specific rewriting.
 * Examples: "media/assets/2026/09/sample-uuid.webp", "custom-bucket-key-12345".
 */
public record StorageKey(
        String value
) {

    private static final int MAX_LENGTH =
            500;

    public StorageKey {
        Objects.requireNonNull(
                value,
                "StorageKey cannot be null."
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "StorageKey cannot be blank."
            );
        }

        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "StorageKey cannot exceed "
                            + MAX_LENGTH
                            + " characters."
            );
        }
    }

    public static StorageKey of(
            String value
    ) {
        return new StorageKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
