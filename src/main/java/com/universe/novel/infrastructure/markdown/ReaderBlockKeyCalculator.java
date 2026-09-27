package com.universe.novel.infrastructure.markdown;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic Reader block locator calculator.
 * Computes chapter-local locator fingerprints for canonical semantic Reader blocks.
 */
final class ReaderBlockKeyCalculator {

    private static final Pattern SPECIAL_SPACES_PATTERN =
            Pattern.compile("[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000\\uFEFF]");
    private static final Pattern MULTI_WHITESPACE_PATTERN =
            Pattern.compile("\\s+");

    private ReaderBlockKeyCalculator() {}

    /**
     * Normalizes prose text by applying Unicode NFC, collapsing whitespace,
     * converting newlines to spaces, and trimming leading/trailing whitespace.
     */
    static String normalizeProseText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        normalized = SPECIAL_SPACES_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = normalized.replace("\r\n", " ")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("\t", " ");
        normalized = MULTI_WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ");
        return normalized.trim();
    }

    /**
     * Normalizes code block text by applying Unicode NFC, normalizing line endings,
     * and removing leading and trailing blank lines only while preserving whitespace
     * and indentation on all nonblank content lines.
     */
    static String normalizeCodeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        normalized = normalized.replace("\r\n", "\n").replace("\r", "\n");

        String[] lines = normalized.split("\n", -1);
        int first = -1;
        int last = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                if (first == -1) {
                    first = i;
                }
                last = i;
            }
        }
        if (first == -1) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = first; i <= last; i++) {
            if (i > first) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * Formats canonical normalized table row cells into an unambiguous length-prefixed representation.
     * Each cell is normalized using normalizeProseText, then encoded as "<length>:<normalizedCell>;".
     */
    static String formatTableRowFingerprintContent(List<String> rawCellTexts) {
        if (rawCellTexts == null || rawCellTexts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String rawCell : rawCellTexts) {
            String normalizedCell = normalizeProseText(rawCell);
            sb.append(normalizedCell.length()).append(':').append(normalizedCell).append(';');
        }
        return sb.toString();
    }

    /**
     * Computes a deterministic SHA-256 fingerprint hex substring for a block.
     */
    static String computeFingerprint(String blockKind, String normalizedContent) {
        String input = blockKind + ":" + normalizedContent;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Formats an opaque, HTML data-* attribute safe block key.
     */
    static String formatBlockKey(String fingerprint, int occurrence) {
        return "blk-" + fingerprint + "-" + occurrence;
    }
}
