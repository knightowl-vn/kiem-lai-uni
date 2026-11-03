package com.universe.screen.application.dto;

import com.universe.screen.domain.ScreenEntryType;

import java.util.Objects;
import java.util.UUID;

/**
 * Public application DTO representing an individual published Screen entry.
 */
public record PublicScreenEntryDTO(
        UUID id,
        String slug,
        ScreenEntryType type,
        Integer number,
        String title,
        String synopsis,
        Long durationSeconds,
        UUID thumbnailMediaAssetId,
        boolean hasPlayableVideo
) {

    public PublicScreenEntryDTO {
        Objects.requireNonNull(id, "Entry ID cannot be null.");
        Objects.requireNonNull(slug, "Slug cannot be null.");
        Objects.requireNonNull(type, "ScreenEntryType cannot be null.");
        Objects.requireNonNull(title, "Title cannot be null.");
    }
}
