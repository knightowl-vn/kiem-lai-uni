package com.universe.novel.application.locator;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, intentionally small parser for recognizing chapter number navigational queries.
 *
 * Recognized patterns (case-insensitive, whitespace-collapsed):
 * - 123
 * - chương 123
 * - chuong 123
 * - chapter 123
 */
public final class ChapterNumberQueryParser {

    private static final int MAX_QUERY_LENGTH = 200;

    private static final Pattern CHAPTER_NUMBER_PATTERN = Pattern.compile(
            "^(?:(?:chương|chuong|chapter)\\s+)?(\\d{1,9})$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    private ChapterNumberQueryParser() {
    }

    /**
     * Attempts to parse a normalized or raw user query into a positive chapter number.
     *
     * @param query raw or normalized query string
     * @return Optional containing parsed positive chapter number, or empty if not a chapter number intent
     */
    public static Optional<Integer> parseChapterNumber(String query) {
        if (query == null) {
            return Optional.empty();
        }

        String normalized = query.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return Optional.empty();
        }

        if (normalized.length() > MAX_QUERY_LENGTH) {
            normalized = normalized.substring(0, MAX_QUERY_LENGTH).trim();
        }

        Matcher matcher = CHAPTER_NUMBER_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        try {
            int number = Integer.parseInt(matcher.group(1));
            if (number < 1) {
                return Optional.empty();
            }
            return Optional.of(number);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
