package com.universe.screen.application.dto;

import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenStructure;

import java.util.Objects;
import java.util.UUID;

/**
 * Public application DTO representing detailed metadata of a published Screen entry.
 */
public record PublicScreenEntryDetailDTO(
        UUID titleId,
        String titleSlug,
        String titleDisplayName,
        ScreenStructure titleStructure,
        UUID entryId,
        String entrySlug,
        ScreenEntryType entryType,
        Integer entryNumber,
        String entryTitle,
        String entrySynopsis,
        Long durationSeconds,
        UUID thumbnailMediaAssetId,
        boolean hasPlayableVideo
) {

    public PublicScreenEntryDetailDTO {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");
        Objects.requireNonNull(titleSlug, "Title slug cannot be null.");
        Objects.requireNonNull(titleDisplayName, "Title display name cannot be null.");
        Objects.requireNonNull(titleStructure, "Title structure cannot be null.");
        Objects.requireNonNull(entryId, "Entry ID cannot be null.");
        Objects.requireNonNull(entrySlug, "Entry slug cannot be null.");
        Objects.requireNonNull(entryType, "Entry type cannot be null.");
        Objects.requireNonNull(entryTitle, "Entry title cannot be null.");
    }
}
