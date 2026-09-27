package com.universe.novel.application.locator;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalizer and ranking evaluator for chapter title navigation search.
 * Handles accent folding, case folding, LIKE escaping, and deterministic đ/Đ -> d folding.
 */
public final class ChapterTitleSearchNormalizer {

    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("[\\p{InCombiningDiacriticalMarks}\\p{M}]+");

    private ChapterTitleSearchNormalizer() {
    }

    /**
     * Folds all đ and Đ occurrences to standard d and D.
     *
     * @param input source string
     * @return string with all đ/Đ replaced by d/D
     */
    public static String foldD(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input.replace('đ', 'd').replace('Đ', 'D');
    }

    /**
     * Normalizes a string for accent-insensitive, case-insensitive comparison.
     * Folds đ/Đ to d, removes all diacritical marks, lowercases, and collapses whitespace.
     *
     * @param input source text
     * @return normalized text
     */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String foldedD = foldD(input);
        String decomposed = Normalizer.normalize(foldedD, Normalizer.Form.NFD);
        String stripped = DIACRITICS_PATTERN.matcher(decomposed).replaceAll("");
        return stripped.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /**
     * Escapes SQL LIKE wildcards (%, _, \).
     * Must be called on LIKE search patterns to prevent user characters from acting as wildcards.
     *
     * @param input raw query string
     * @return escaped query string safe for SQL LIKE concatenation
     */
    public static String escapeLikeWildcards(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * Evaluates the title match rank against a search query:
     * - Rank 1: Exact title match
     * - Rank 2: Title prefix match
     * - Rank 3: Title contains match
     *
     * @param title chapter title
     * @param query search query
     * @return rank integer (1, 2, or 3)
     */
    public static int determineTitleRank(String title, String query) {
        String normTitle = normalize(title);
        String normQuery = normalize(query);

        if (normQuery.isEmpty() || normTitle.isEmpty()) {
            return 3;
        }
        if (normTitle.equals(normQuery)) {
            return 1;
        }
        if (normTitle.startsWith(normQuery)) {
            return 2;
        }
        return 3;
    }
}
