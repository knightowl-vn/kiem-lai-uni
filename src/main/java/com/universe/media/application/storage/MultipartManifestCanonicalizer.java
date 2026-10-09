package com.universe.media.application.storage;

import com.universe.media.domain.MediaUploadSession;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic canonical manifest validator and SHA-256 fingerprint builder.
 *
 * <p>Enforces the locked multipart policy:
 * <ul>
 *     <li>Sorts unordered submitted parts by ascending part number;</li>
 *     <li>Rejects duplicate, missing, unexpected, or out-of-range part numbers;</li>
 *     <li>Validates each part size and total declared size against the session policy;</li>
 *     <li>Preserves opaque provider ETag identity and case (never converts to lowercase or assumes hex);</li>
 *     <li>Preserves {@code W/} weak ETag prefix;</li>
 *     <li>Strips transport wrapping quotes only when justified by provider/HTTP conventions;</li>
 *     <li>Rejects invalid control characters (CR, LF, NUL, DEL, and non-printable characters);</li>
 *     <li>Enforces bounded ETag lengths (max 256 bytes) and total canonical manifest size (max 1 MiB);</li>
 *     <li>Encodes canonical lines into unambiguous length-prefixed format: {@code kiemlai-part-manifest-v2};</li>
 *     <li>Computes SHA-256 digest of canonical bytes and formats as a 64-character lowercase hex string.</li>
 * </ul>
 */
public final class MultipartManifestCanonicalizer {

    public static final String CANONICAL_VERSION_PREFIX = "kiemlai-part-manifest-v2\n";
    public static final int MAX_ETAG_LENGTH = 256;
    public static final int MAX_CANONICAL_MANIFEST_BYTES = 1024 * 1024; // 1 MiB

    private MultipartManifestCanonicalizer() {
    }

    /**
     * Normalizes an ETag by removing outer transport wrapping double quotes if present, while
     * strictly preserving character casing, non-hexadecimal tokens, and weak ETag {@code W/} prefix.
     * Rejects control characters, invalid whitespace, and ambiguous quote syntax.
     */
    public static String normalizeETag(String eTag) {
        if (eTag == null) {
            throw new IllegalArgumentException("eTag cannot be null.");
        }
        if (eTag.isEmpty()) {
            throw new IllegalArgumentException("eTag cannot be empty.");
        }
        if (eTag.startsWith(" ") || eTag.endsWith(" ")) {
            throw new IllegalArgumentException("eTag must not contain leading or trailing whitespace.");
        }

        // Strictly reject control characters (CR, LF, NUL, DEL, any char <= 0x1F or == 0x7F) and Unicode surrogates
        for (int i = 0; i < eTag.length(); i++) {
            char c = eTag.charAt(i);
            if (c <= 0x1F || c == 0x7F || Character.isSurrogate(c)) {
                throw new IllegalArgumentException(
                        String.format("eTag contains invalid control character or surrogate at index %d: 0x%04x", i, (int) c)
                );
            }
        }

        String candidate = eTag;

        // Case 1: Weak ETag with wrapping quotes: W/"..." -> W/...
        if (candidate.startsWith("W/\"")) {
            if (!candidate.endsWith("\"") || candidate.length() < 5) {
                throw new IllegalArgumentException("Malformed or unbalanced quotes in weak eTag: " + candidate);
            }
            String inner = candidate.substring(3, candidate.length() - 1);
            if (inner.isEmpty()) {
                throw new IllegalArgumentException("eTag payload inside quotes cannot be empty.");
            }
            if (inner.indexOf('"') >= 0) {
                throw new IllegalArgumentException("eTag payload contains invalid embedded double quote.");
            }
            candidate = "W/" + inner;
        }
        // Case 2: Strong ETag with wrapping quotes: "..." -> ...
        else if (candidate.startsWith("\"")) {
            if (!candidate.endsWith("\"") || candidate.length() < 3) {
                throw new IllegalArgumentException("Malformed or unbalanced quotes in eTag: " + candidate);
            }
            String inner = candidate.substring(1, candidate.length() - 1);
            if (inner.isEmpty()) {
                throw new IllegalArgumentException("eTag payload inside quotes cannot be empty.");
            }
            if (inner.indexOf('"') >= 0) {
                throw new IllegalArgumentException("eTag payload contains invalid embedded double quote.");
            }
            candidate = inner;
        }
        // Case 3: Raw token without transport quotes
        else {
            if (candidate.endsWith("\"")) {
                throw new IllegalArgumentException("Malformed or unbalanced trailing quote in eTag: " + candidate);
            }
            if (candidate.indexOf('"') >= 0) {
                throw new IllegalArgumentException("eTag contains invalid double quote.");
            }
        }

        byte[] candidateUtf8 = candidate.getBytes(StandardCharsets.UTF_8);
        if (candidateUtf8.length == 0 || candidateUtf8.length > MAX_ETAG_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Normalized eTag UTF-8 byte length must be between 1 and %d, got %d", MAX_ETAG_LENGTH, candidateUtf8.length)
            );
        }

        return candidate;
    }

    /**
     * Builds the deterministic unambiguous canonical byte array for the validated manifest.
     *
     * <p>Encoding format:
     * <pre>
     * kiemlai-part-manifest-v2\n
     * &lt;partNumber&gt;:&lt;sizeBytes&gt;:&lt;etagByteLength&gt;:&lt;normalizedETagUtf8Bytes&gt;\n
     * ...
     * </pre>
     */
    public static byte[] canonicalizeToBytes(MediaUploadSession session, List<MultipartManifestPart> manifest) {
        Objects.requireNonNull(session, "session cannot be null.");
        Objects.requireNonNull(manifest, "manifest cannot be null.");

        if (manifest.isEmpty()) {
            throw new IllegalArgumentException("Manifest cannot be empty.");
        }

        int expectedPartCount = session.getExpectedPartCount();
        if (manifest.size() != expectedPartCount) {
            throw new IllegalArgumentException(
                    String.format("Manifest part count mismatch: expected %d parts, got %d", expectedPartCount, manifest.size())
            );
        }

        // 1. Sort unordered input by partNumber
        List<MultipartManifestPart> sorted = new ArrayList<>(manifest.size());
        for (int i = 0; i < manifest.size(); i++) {
            MultipartManifestPart part = manifest.get(i);
            if (part == null) {
                throw new IllegalArgumentException("Manifest cannot contain null parts; index " + i + " is null.");
            }
            sorted.add(part);
        }
        sorted.sort(Comparator.comparingInt(MultipartManifestPart::partNumber));

        // 2. Validate contiguous part numbers: 1..expectedPartCount with no gaps or duplicates
        long fixedPartSize = session.getPartSizeBytes();
        long totalDeclaredSize = session.getDeclaredSizeBytes();
        long accumulatedSize = 0L;

        for (int i = 0; i < sorted.size(); i++) {
            MultipartManifestPart current = sorted.get(i);
            int expectedPartNumber = i + 1;

            if (current.partNumber() != expectedPartNumber) {
                if (i > 0 && current.partNumber() == sorted.get(i - 1).partNumber()) {
                    throw new IllegalArgumentException("Duplicate part number detected: " + current.partNumber());
                } else {
                    throw new IllegalArgumentException(
                            String.format("Missing or out-of-range part number: expected %d, got %d", expectedPartNumber, current.partNumber())
                    );
                }
            }

            // 3. Validate part sizes using the locked multipart policy
            if (i < expectedPartCount - 1) {
                // Non-final parts must be exactly fixedPartSize (25 MiB)
                if (current.sizeBytes() != fixedPartSize) {
                    throw new IllegalArgumentException(
                            String.format("Part %d size must be exactly %d bytes, got %d", current.partNumber(), fixedPartSize, current.sizeBytes())
                    );
                }
            } else {
                // Final part must be exactly the remainder
                long expectedLastPartSize = totalDeclaredSize - ((long) (expectedPartCount - 1) * fixedPartSize);
                if (current.sizeBytes() != expectedLastPartSize) {
                    throw new IllegalArgumentException(
                            String.format("Last part %d size must be exactly %d bytes, got %d", current.partNumber(), expectedLastPartSize, current.sizeBytes())
                    );
                }
            }

            accumulatedSize += current.sizeBytes();
        }

        if (accumulatedSize != totalDeclaredSize) {
            throw new IllegalArgumentException(
                    String.format("Total parts size (%d bytes) does not match session declaredSizeBytes (%d bytes)",
                            accumulatedSize, totalDeclaredSize)
            );
        }

        // 4. Build unambiguous versioned canonical byte representation with length-prefixed fields
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] versionBytes = CANONICAL_VERSION_PREFIX.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(versionBytes);

        for (MultipartManifestPart part : sorted) {
            String normalizedETag = normalizeETag(part.eTag());
            byte[] etagBytes = normalizedETag.getBytes(StandardCharsets.UTF_8);

            String fieldPrefix = part.partNumber() + ":" + part.sizeBytes() + ":" + etagBytes.length + ":";
            out.writeBytes(fieldPrefix.getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(etagBytes);
            out.write((byte) '\n');
        }

        byte[] canonicalBytes = out.toByteArray();
        if (canonicalBytes.length > MAX_CANONICAL_MANIFEST_BYTES) {
            throw new IllegalArgumentException(
                    String.format("Canonical manifest size exceeds limit of %d bytes, got %d", MAX_CANONICAL_MANIFEST_BYTES, canonicalBytes.length)
            );
        }

        return canonicalBytes;
    }

    /**
     * Validates the submitted part manifest against the upload session constraints and builds
     * its canonical SHA-256 fingerprint.
     *
     * @param session the upload session defining the expected part count and sizes
     * @param manifest the submitted manifest parts (may be unordered)
     * @return lowercase 64-character hexadecimal SHA-256 manifest fingerprint
     */
    public static String validateAndCanonicalize(MediaUploadSession session, List<MultipartManifestPart> manifest) {
        byte[] canonicalBytes = canonicalizeToBytes(session, manifest);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalBytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available.", e);
        }
    }
}
