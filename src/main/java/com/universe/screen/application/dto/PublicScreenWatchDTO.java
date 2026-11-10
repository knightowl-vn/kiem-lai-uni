package com.universe.screen.application.dto;

import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Public application DTO representing the complete data contract for public Screen watch playback.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Only published title and entry metadata are exposed;</li>
 *   <li>Video delivery URL is present only if approved and playable;</li>
 *   <li>Previous and next entry navigation adhere to deterministic ordering;</li>
 *   <li>Playlist contains all published entries in canonical display order;</li>
 *   <li>Zero exposure of persistence entities, storage object keys, or private provider credentials.</li>
 * </ul>
 */
public record PublicScreenWatchDTO(
        UUID titleId,
        String titleSlug,
        String titleDisplayName,
        ScreenStructure titleStructure,
        ScreenPresentation titlePresentation,
        UUID posterMediaAssetId,
        UUID backdropMediaAssetId,
        UUID entryId,
        String entrySlug,
        ScreenEntryType entryType,
        Integer entryNumber,
        String entryTitle,
        String entrySynopsis,
        Long durationSeconds,
        UUID thumbnailMediaAssetId,
        boolean isPlayable,
        String videoDeliveryUrl,
        String videoMimeType,
        PublicScreenEntryDTO previousEntry,
        PublicScreenEntryDTO nextEntry,
        List<PublicScreenEntryDTO> playlist,
        Long effectiveIntroStartSeconds,
        Long effectiveIntroEndSeconds,
        boolean hasEffectiveIntro,
        Long effectiveOutroStartSeconds,
        Long effectiveOutroEndSeconds,
        boolean hasEffectiveOutro
) {

    public PublicScreenWatchDTO {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");
        Objects.requireNonNull(titleSlug, "Title slug cannot be null.");
        Objects.requireNonNull(titleDisplayName, "Title display name cannot be null.");
        Objects.requireNonNull(titleStructure, "Title structure cannot be null.");
        Objects.requireNonNull(titlePresentation, "Title presentation cannot be null.");
        Objects.requireNonNull(entryId, "Entry ID cannot be null.");
        Objects.requireNonNull(entrySlug, "Entry slug cannot be null.");
        Objects.requireNonNull(entryType, "Entry type cannot be null.");
        Objects.requireNonNull(entryTitle, "Entry title cannot be null.");
        playlist = playlist != null ? List.copyOf(playlist) : List.of();
    }

    /**
     * Backward-compatible convenience constructor omitting timeline metadata.
     */
    public PublicScreenWatchDTO(
            UUID titleId,
            String titleSlug,
            String titleDisplayName,
            ScreenStructure titleStructure,
            ScreenPresentation titlePresentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            UUID entryId,
            String entrySlug,
            ScreenEntryType entryType,
            Integer entryNumber,
            String entryTitle,
            String entrySynopsis,
            Long durationSeconds,
            UUID thumbnailMediaAssetId,
            boolean isPlayable,
            String videoDeliveryUrl,
            String videoMimeType,
            PublicScreenEntryDTO previousEntry,
            PublicScreenEntryDTO nextEntry,
            List<PublicScreenEntryDTO> playlist
    ) {
        this(
                titleId,
                titleSlug,
                titleDisplayName,
                titleStructure,
                titlePresentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                entryId,
                entrySlug,
                entryType,
                entryNumber,
                entryTitle,
                entrySynopsis,
                durationSeconds,
                thumbnailMediaAssetId,
                isPlayable,
                videoDeliveryUrl,
                videoMimeType,
                previousEntry,
                nextEntry,
                playlist,
                null,
                null,
                false,
                null,
                null,
                false
        );
    }

    /**
     * Backward-compatible convenience constructor omitting title backdropMediaAssetId.
     */
    public PublicScreenWatchDTO(
            UUID titleId,
            String titleSlug,
            String titleDisplayName,
            ScreenStructure titleStructure,
            ScreenPresentation titlePresentation,
            UUID posterMediaAssetId,
            UUID entryId,
            String entrySlug,
            ScreenEntryType entryType,
            Integer entryNumber,
            String entryTitle,
            String entrySynopsis,
            Long durationSeconds,
            UUID thumbnailMediaAssetId,
            boolean isPlayable,
            String videoDeliveryUrl,
            String videoMimeType,
            PublicScreenEntryDTO previousEntry,
            PublicScreenEntryDTO nextEntry,
            List<PublicScreenEntryDTO> playlist
    ) {
        this(
                titleId,
                titleSlug,
                titleDisplayName,
                titleStructure,
                titlePresentation,
                posterMediaAssetId,
                null,
                entryId,
                entrySlug,
                entryType,
                entryNumber,
                entryTitle,
                entrySynopsis,
                durationSeconds,
                thumbnailMediaAssetId,
                isPlayable,
                videoDeliveryUrl,
                videoMimeType,
                previousEntry,
                nextEntry,
                playlist
        );
    }
}
