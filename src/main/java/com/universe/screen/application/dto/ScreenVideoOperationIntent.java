package com.universe.screen.application.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object representing parsed operation intent from a Screen upload session's clientTag.
 *
 * <p><strong>Fail-Closed Policy:</strong>
 * <ul>
 *   <li>Only actual supported INITIAL tags (e.g. "screen-video", "screen-video-initial") map to INITIAL.</li>
 *   <li>Only valid REPLACEMENT tags with a valid UUID map to REPLACEMENT.</li>
 *   <li>Absent, unknown, or malformed tags (including malformed replacement UUIDs) map to UNKNOWN.</li>
 *   <li>UNKNOWN intent must never produce canRetry=true.</li>
 * </ul>
 */
public record ScreenVideoOperationIntent(
        ScreenVideoIntentType type,
        UUID replacementExpectedAssetId
) {
    public static final String CLIENT_TAG_INITIAL = "screen-video";
    public static final String CLIENT_TAG_INITIAL_ALT = "screen-video-initial";
    public static final String CLIENT_TAG_REPLACE_PREFIX = "screen-replace:";
    public static final String CLIENT_TAG_REPLACE_PREFIX_ALT = "screen-video-replacement:";

    public ScreenVideoOperationIntent {
        Objects.requireNonNull(type, "ScreenVideoIntentType cannot be null.");
    }

    public boolean isReplacement() {
        return type == ScreenVideoIntentType.REPLACEMENT;
    }

    public boolean isInitial() {
        return type == ScreenVideoIntentType.INITIAL;
    }

    public boolean isUnknown() {
        return type == ScreenVideoIntentType.UNKNOWN;
    }

    /**
     * Parses clientTag into a ScreenVideoOperationIntent.
     *
     * <p>Recognizes:
     * <ul>
     *   <li>Initial tags: "screen-video", "screen-video-initial"</li>
     *   <li>Replacement tags: "screen-replace:<uuid>", "screen-video-replacement:<uuid>"</li>
     * </ul>
     *
     * <p>Fails closed: any absent, unknown, or malformed tag defaults to {@link ScreenVideoIntentType#UNKNOWN}.
     * Malformed replacement tags are NEVER reinterpreted as INITIAL.
     *
     * @param clientTag raw client tag from upload session
     * @return parsed operation intent
     */
    public static ScreenVideoOperationIntent parse(String clientTag) {
        if (clientTag == null || clientTag.isBlank()) {
            return new ScreenVideoOperationIntent(ScreenVideoIntentType.UNKNOWN, null);
        }
        String trimmed = clientTag.trim();

        if (CLIENT_TAG_INITIAL.equals(trimmed) || CLIENT_TAG_INITIAL_ALT.equals(trimmed)) {
            return new ScreenVideoOperationIntent(ScreenVideoIntentType.INITIAL, null);
        }

        if (trimmed.startsWith(CLIENT_TAG_REPLACE_PREFIX) || trimmed.startsWith(CLIENT_TAG_REPLACE_PREFIX_ALT)) {
            int colonIndex = trimmed.indexOf(':');
            String rawUuid = (colonIndex >= 0 && colonIndex + 1 < trimmed.length())
                    ? trimmed.substring(colonIndex + 1).trim()
                    : "";
            if (!rawUuid.isEmpty()) {
                try {
                    UUID expectedAssetId = UUID.fromString(rawUuid);
                    return new ScreenVideoOperationIntent(ScreenVideoIntentType.REPLACEMENT, expectedAssetId);
                } catch (IllegalArgumentException ignored) {
                    // Malformed replacement UUID fails closed to UNKNOWN, never INITIAL
                }
            }
            return new ScreenVideoOperationIntent(ScreenVideoIntentType.UNKNOWN, null);
        }

        return new ScreenVideoOperationIntent(ScreenVideoIntentType.UNKNOWN, null);
    }
}
