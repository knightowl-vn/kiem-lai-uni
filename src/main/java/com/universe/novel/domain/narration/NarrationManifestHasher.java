package com.universe.novel.domain.narration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Domain utility that computes the canonical SHA-256 manifest hash for an ordered list
 * of {@link NarrationTextSegment}s.
 * <p>
 * Canonical format:
 * For each segment from index 0 to N-1:
 * {@code <index>:<contentHash>\n}
 * <p>
 * If the list of segments is empty, the hash is the SHA-256 digest of the empty string {@code ""}:
 * {@code "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}.
 */
public final class NarrationManifestHasher {

    public static final String EMPTY_MANIFEST_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private NarrationManifestHasher() {
    }

    /**
     * Computes the deterministic 64-character lowercase hexadecimal SHA-256 manifest hash
     * for the provided ordered list of {@link NarrationTextSegment}s.
     *
     * @param segments the ordered speakable text segments
     * @return 64-character lowercase hex SHA-256 digest string
     * @throws NullPointerException     if {@code segments} is null or contains null elements
     * @throws IllegalArgumentException if segment indices are not strictly contiguous from 0 to N-1
     */
    public static String computeManifestHash(List<NarrationTextSegment> segments) {
        Objects.requireNonNull(segments, "segments list must not be null.");

        if (segments.isEmpty()) {
            return EMPTY_MANIFEST_HASH;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            NarrationTextSegment segment = segments.get(i);
            Objects.requireNonNull(segment, "Segment at index " + i + " must not be null.");
            if (segment.index() != i) {
                throw new IllegalArgumentException(
                        "Segment at position " + i + " has invalid index " + segment.index()
                                + ". Expected strictly contiguous 0-based indices."
                );
            }
            sb.append(segment.index())
                    .append(':')
                    .append(segment.contentHash())
                    .append('\n');
        }

        return computeSha256(sb.toString());
    }

    private static String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
