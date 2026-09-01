package com.universe.media.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Opaque identifier representing the underlying storage provider or bucket configuration.
 *
 * Does not hard-code vendor-specific enums.
 * Examples: "cloudinary", "s3-main", "r2-archive", "local".
 */
public record StorageProviderId(
        String value
) {

    private static final int MAX_LENGTH =
            50;

    private static final String VALID_PATTERN =
            "^[a-z0-9]+(?:[-_][a-z0-9]+)*$";

    public StorageProviderId {
        Objects.requireNonNull(
                value,
                "StorageProviderId cannot be null."
        );

        String normalized =
                value.trim().toLowerCase(Locale.ROOT);

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "StorageProviderId cannot be blank."
            );
        }

        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "StorageProviderId cannot exceed "
                            + MAX_LENGTH
                            + " characters."
            );
        }

        if (!normalized.matches(VALID_PATTERN)) {
            throw new IllegalArgumentException(
                    "StorageProviderId must contain only lowercase letters, digits, dashes, and underscores."
            );
        }

        value = normalized;
    }

    public static StorageProviderId of(
            String value
    ) {
        return new StorageProviderId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
