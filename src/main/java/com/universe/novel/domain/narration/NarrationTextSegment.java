package com.universe.novel.domain.narration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable value object representing a deterministic speakable narration segment.
 *
 * @param index          0-based ordered position of the segment within the chapter
 * @param text           normalized speakable plain text of the segment
 * @param characterCount character count of the normalized text
 * @param contentHash    SHA-256 lowercase hexadecimal hash of the exact UTF-8 segment text
 */
public record NarrationTextSegment(
        int index,
        String text,
        int characterCount,
        String contentHash
) {

    public NarrationTextSegment {
        if (index < 0) {
            throw new IllegalArgumentException("Segment index must be non-negative.");
        }
        Objects.requireNonNull(text, "Segment text must not be null.");
        Objects.requireNonNull(contentHash, "Content hash must not be null.");
        if (characterCount != text.length()) {
            throw new IllegalArgumentException("Character count must match text length.");
        }
        String expectedHash = computeSha256(text);
        if (!contentHash.equals(expectedHash)) {
            throw new IllegalArgumentException("Content hash does not match SHA-256 digest of segment text.");
        }
    }

    /**
     * Factory method creating a segment from index and normalized text,
     * automatically computing character count and SHA-256 content hash.
     *
     * @param index 0-based ordered position
     * @param text  normalized segment text
     * @return a new immutable NarrationTextSegment
     */
    public static NarrationTextSegment of(int index, String text) {
        Objects.requireNonNull(text, "Segment text must not be null.");
        String hash = computeSha256(text);
        return new NarrationTextSegment(index, text, text.length(), hash);
    }

    /**
     * Computes the lowercase 64-character SHA-256 hexadecimal digest of UTF-8 text.
     *
     * @param text input text
     * @return lowercase hex string
     */
    public static String computeSha256(String text) {
        Objects.requireNonNull(text, "Text for hash computation must not be null.");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
