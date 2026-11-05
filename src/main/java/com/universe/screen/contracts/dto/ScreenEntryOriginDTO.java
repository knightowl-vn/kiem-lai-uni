package com.universe.screen.contracts.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Contract-safe immutable projection representing an entry origin for public Screen comment badges.
 */
public record ScreenEntryOriginDTO(
        UUID entryId,
        String slug,
        String displayBadge
) {
    public ScreenEntryOriginDTO {
        Objects.requireNonNull(entryId, "entryId cannot be null");
        Objects.requireNonNull(displayBadge, "displayBadge cannot be null");
    }
}
