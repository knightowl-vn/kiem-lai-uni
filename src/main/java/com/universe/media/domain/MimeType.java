package com.universe.media.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Value Object representing a validated MIME content type.
 *
 * Examples: "image/webp", "audio/mpeg", "video/mp4", "application/pdf".
 */
public record MimeType(
        String value
) {

    private static final int MAX_LENGTH =
            100;

    private static final String MIME_PATTERN =
            "^[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+$";

    public MimeType {
        Objects.requireNonNull(
                value,
                "MimeType cannot be null."
        );

        String normalized =
                value.trim().toLowerCase(Locale.ROOT);

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "MimeType cannot be blank."
            );
        }

        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "MimeType cannot exceed "
                            + MAX_LENGTH
                            + " characters."
            );
        }

        if (!normalized.matches(MIME_PATTERN)) {
            throw new IllegalArgumentException(
                    "MimeType is not in a valid format ('type/subtype')."
            );
        }

        value = normalized;
    }

    public static MimeType of(
            String value
    ) {
        return new MimeType(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
