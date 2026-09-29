package com.universe.identity.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Normalization utility for Identity publicHandle.
 *
 * <p>Enforces the canonical publicHandle contract:
 * <ul>
 *   <li>Regex format: {@code ^[a-z0-9_]{3,40}$}</li>
 *   <li>Normalizes initial display name (Vietnamese diacritics transliterated, lowercase, non-alphanumeric -> underscore)</li>
 *   <li>Collapses consecutive underscores and trims leading/trailing underscores</li>
 *   <li>Provides neutral deterministic fallback derived from immutable user UUID if display name cannot produce valid handle</li>
 *   <li>Strictly independent of email/username</li>
 * </ul>
 */
public final class PublicHandleNormalizer {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 40;
    public static final Pattern HANDLE_PATTERN = Pattern.compile("^[a-z0-9_]{3,40}$");

    private PublicHandleNormalizer() {
    }

    /**
     * Normalizes a raw display name into a clean public handle candidate string.
     *
     * @param displayName raw display name
     * @return normalized candidate string (may be empty or shorter than MIN_LENGTH)
     */
    public static String normalizeCandidate(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "";
        }

        String trimmed = displayName.trim();
        while (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.isEmpty()) {
            return "";
        }

        // Transliterate Vietnamese specific characters
        String dFolded = trimmed
                .replace('Đ', 'd')
                .replace('đ', 'd')
                .replace('Ð', 'd');

        // Decompose accents and remove diacritical marks
        String nfd = Normalizer.normalize(dFolded, Normalizer.Form.NFD);
        String stripped = nfd.replaceAll("\\p{M}", "");

        // Lowercase
        String lower = stripped.toLowerCase(Locale.ROOT);

        // Replace any character not in [a-z0-9] with underscore
        String replaced = lower.replaceAll("[^a-z0-9]", "_");

        // Collapse consecutive underscores
        String collapsed = replaced.replaceAll("_+", "_");

        // Trim leading and trailing underscores
        String candidate = collapsed.replaceAll("^_+|_+$", "");

        if (candidate.length() > MAX_LENGTH) {
            candidate = candidate.substring(0, MAX_LENGTH).replaceAll("_+$", "");
        }

        return candidate;
    }

    /**
     * Checks whether a given string is a strictly valid publicHandle according to domain rules.
     *
     * @param handle handle candidate to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValid(String handle) {
        return handle != null && HANDLE_PATTERN.matcher(handle).matches();
    }

    public static boolean isValidHandle(String handle) {
        return isValid(handle);
    }

    /**
     * Generates a base handle candidate from display name with deterministic fallback to user UUID.
     *
     * @param displayName user display name
     * @param userId immutable user UUID
     * @return valid publicHandle candidate matching {@code ^[a-z0-9_]{3,40}$}
     */
    public static String generateCandidate(String displayName, UUID userId) {
        Objects.requireNonNull(userId, "userId cannot be null");
        String candidate = normalizeCandidate(displayName);
        if (candidate.length() >= MIN_LENGTH) {
            return candidate;
        }

        // Deterministic fallback derived from immutable user UUID (hex without hyphens)
        String uuidHex = userId.toString().replace("-", "").toLowerCase(Locale.ROOT);
        String fallback = "user_" + uuidHex.substring(0, Math.min(12, uuidHex.length()));
        return fallback;
    }

    /**
     * Generates a collision resolution candidate using UUID material.
     *
     * @param baseCandidate base candidate handle
     * @param userId immutable user UUID
     * @param attempt attempt sequence index (1, 2, 3...)
     * @return deterministic candidate matching {@code ^[a-z0-9_]{3,40}$}
     */
    public static String generateCollisionCandidate(String baseCandidate, UUID userId, int attempt) {
        Objects.requireNonNull(userId, "userId cannot be null");
        String uuidHex = userId.toString().replace("-", "").toLowerCase(Locale.ROOT);

        String base = (baseCandidate == null || baseCandidate.isBlank())
                ? "user"
                : baseCandidate.replaceAll("^_+|_+$", "");

        if (base.length() < MIN_LENGTH) {
            base = "user";
        }

        int discriminatorChars = Math.min(4 * attempt, Math.min(16, uuidHex.length()));
        String discriminator = uuidHex.substring(0, discriminatorChars);

        int maxBaseLen = MAX_LENGTH - 1 - discriminator.length();
        if (attempt > 3) {
            // Append attempt counter for extra collision margin
            String suffix = "_" + attempt;
            maxBaseLen -= suffix.length();
            if (base.length() > maxBaseLen) {
                base = base.substring(0, Math.max(1, maxBaseLen)).replaceAll("_+$", "");
            }
            return base + "_" + discriminator + suffix;
        }

        if (base.length() > maxBaseLen) {
            base = base.substring(0, Math.max(1, maxBaseLen)).replaceAll("_+$", "");
        }

        return base + "_" + discriminator;
    }
}
