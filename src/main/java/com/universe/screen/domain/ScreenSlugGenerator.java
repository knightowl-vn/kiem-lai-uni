package com.universe.screen.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure domain utility for generating URL slugs from Screen catalog titles,
 * with full Vietnamese diacritics normalization including 'đ' and 'Đ'.
 */
public final class ScreenSlugGenerator {

    private static final Pattern DIACRITICAL_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("(^-+)|(-+$)");
    public static final String DEFAULT_FALLBACK_SLUG = "screen-title";
    public static final int MAX_SLUG_LENGTH = ScreenSlugValidator.MAX_SLUG_LENGTH;

    private ScreenSlugGenerator() {
    }

    /**
     * Generates a normalized URL slug from a title string.
     *
     * @param title candidate display title
     * @return lowercase, diacritic-free, single-hyphenated slug adhering to {@link ScreenSlugValidator}
     */
    public static String generate(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_FALLBACK_SLUG;
        }

        String normalized = title.trim().toLowerCase(Locale.ROOT);

        // Normalize Vietnamese 'đ' / 'Đ'
        normalized = normalized.replace('đ', 'd');

        // Decompose unicode diacritics and strip combining marks
        String decomposed = Normalizer.normalize(normalized, Normalizer.Form.NFD);
        String withoutDiacritics = DIACRITICAL_MARKS.matcher(decomposed).replaceAll("");

        // Replace all non-alphanumeric characters with hyphens
        String hyphenated = NON_ALPHANUMERIC.matcher(withoutDiacritics).replaceAll("-");

        // Strip leading and trailing hyphens
        String cleaned = EDGE_HYPHENS.matcher(hyphenated).replaceAll("");

        if (cleaned.isBlank()) {
            return DEFAULT_FALLBACK_SLUG;
        }

        if (cleaned.length() > ScreenSlugValidator.MAX_SLUG_LENGTH) {
            cleaned = cleaned.substring(0, ScreenSlugValidator.MAX_SLUG_LENGTH);
            cleaned = EDGE_HYPHENS.matcher(cleaned).replaceAll("");
            if (cleaned.isBlank()) {
                return DEFAULT_FALLBACK_SLUG;
            }
        }

        return cleaned;
    }
}
