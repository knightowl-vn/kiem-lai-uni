package com.universe.interaction.domain;

/**
 * Deterministic sorting modes for discussion root comment feeds.
 */
public enum CommentSortMode {
    FEATURED,
    NEWEST;

    /**
     * Parses a sort mode from string or returns {@link #FEATURED} if null or blank.
     *
     * @param value raw query parameter string
     * @return resolved {@link CommentSortMode}
     * @throws IllegalArgumentException if the provided value is non-blank and invalid
     */
    public static CommentSortMode parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return FEATURED;
        }
        String normalized = value.trim().toUpperCase();
        for (CommentSortMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Invalid comment sort mode: " + value);
    }
}
