package com.universe.interaction.entry.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable public read representation of comment episode origin metadata.
 *
 * @param entryId scalar unique identifier of the origin ScreenEntry (never null)
 * @param badgeText human-readable episode badge text resolved authoritatively from the server (never null)
 * @param entrySlug public URL slug of the origin ScreenEntry for linking (nullable)
 */
public record CommentOriginDTO(
        UUID entryId,
        String badgeText,
        String entrySlug
) {
    public CommentOriginDTO {
        Objects.requireNonNull(entryId, "entryId cannot be null.");
        Objects.requireNonNull(badgeText, "badgeText cannot be null.");
    }
}
