package com.universe.screen.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Package-private validator for Screen catalog URL slugs.
 *
 * <p>Enforces source-consistent slug requirements:
 * <ul>
 *   <li>Non-null, non-blank;</li>
 *   <li>Normalized by trimming outer whitespace and lowercasing;</li>
 *   <li>Maximum 180 characters;</li>
 *   <li>Format matches lowercase alphanumeric words separated by single hyphens (^[a-z0-9]+(?:-[a-z0-9]+)*$).</li>
 * </ul>
 */
final class ScreenSlugValidator {

    public static final int MAX_SLUG_LENGTH = 180;
    private static final String VALID_SLUG_PATTERN = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    private ScreenSlugValidator() {
    }

    /**
     * Validates and normalizes a candidate slug string.
     *
     * @param slug the candidate slug
     * @return the trimmed, lowercase normalized slug
     * @throws NullPointerException if slug is null
     * @throws IllegalArgumentException if slug is blank, exceeds max length, or contains invalid characters
     */
    public static String validate(String slug) {
        Objects.requireNonNull(slug, "Slug cannot be null.");

        String normalized = slug.trim().toLowerCase(Locale.ROOT);

        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Slug cannot be blank.");
        }

        if (normalized.length() > MAX_SLUG_LENGTH) {
            throw new IllegalArgumentException("Slug cannot exceed " + MAX_SLUG_LENGTH + " characters.");
        }

        if (!normalized.matches(VALID_SLUG_PATTERN)) {
            throw new IllegalArgumentException(
                    "Slug may only contain lowercase alphanumeric characters and single hyphens without leading or trailing hyphens."
            );
        }

        return normalized;
    }
}
