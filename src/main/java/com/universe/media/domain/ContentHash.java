package com.universe.media.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Value Object representing the SHA-256 cryptographic digest of a binary asset.
 */
public record ContentHash(
        String value
) {

    private static final int REQUIRED_LENGTH =
            64;

    private static final String HEX_PATTERN =
            "^[0-9a-f]{64}$";

    public ContentHash {
        Objects.requireNonNull(
                value,
                "ContentHash cannot be null."
        );

        String normalized =
                value.trim().toLowerCase(Locale.ROOT);

        if (normalized.length() != REQUIRED_LENGTH
                || !normalized.matches(HEX_PATTERN)) {
            throw new IllegalArgumentException(
                    "ContentHash must be exactly 64 lowercase hexadecimal characters (SHA-256)."
            );
        }

        value = normalized;
    }

    public static ContentHash of(
            String value
    ) {
        return new ContentHash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
