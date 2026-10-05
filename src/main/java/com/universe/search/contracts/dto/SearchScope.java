package com.universe.search.contracts.dto;

import java.util.Locale;

/**
 * Search target scope across bounded contexts.
 */
public enum SearchScope {
    ALL,
    WIKI,
    NOVEL,
    COMMUNITY;

    /**
     * Parses a raw scope string into a SearchScope enum (case-insensitive).
     * Falls back to ALL for null, blank, or unrecognized strings.
     *
     * @param rawScope raw scope string
     * @return parsed SearchScope, or ALL as fallback
     */
    public static SearchScope fromNullable(String rawScope) {
        if (rawScope == null || rawScope.isBlank()) {
            return ALL;
        }
        try {
            return SearchScope.valueOf(rawScope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }
}
