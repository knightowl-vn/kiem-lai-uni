package com.universe.screen.application.dto;

import com.universe.screen.domain.ScreenAiringStatus;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Public application DTO representing detailed metadata of a published Screen title.
 *
 * <p>Contains client-safe metadata approved for public browsing, decoupled from
 * persistence entities, foreign module types, and web presentation views.
 */
public record PublicScreenTitleDetailDTO(
        UUID id,
        String slug,
        String title,
        String originalTitle,
        String synopsis,
        ScreenStructure structure,
        ScreenPresentation presentation,
        UUID posterMediaAssetId,
        UUID backdropMediaAssetId,
        Instant publishedAt,
        Integer latestPublishedEpisodeNumber,
        Integer totalEpisodeCount,
        List<PublicScreenEntryDTO> entries,
        List<PublicScreenFranchiseDTO> franchises,
        ScreenAiringStatus airingStatus,
        Integer releaseYear,
        String country,
        String originalAuthor,
        String director,
        String productionStudio,
        Integer averageDurationMinutes
) {

    public PublicScreenTitleDetailDTO {
        Objects.requireNonNull(id, "Title ID cannot be null.");
        Objects.requireNonNull(slug, "Slug cannot be null.");
        Objects.requireNonNull(title, "Title cannot be null.");
        Objects.requireNonNull(structure, "ScreenStructure cannot be null.");
        Objects.requireNonNull(presentation, "ScreenPresentation cannot be null.");
        entries = entries == null ? List.of() : List.copyOf(entries);
        franchises = franchises == null ? List.of() : List.copyOf(franchises);
    }

    /**
     * Backward-compatible constructor without extended release metadata.
     */
    public PublicScreenTitleDetailDTO(
            UUID id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            Instant publishedAt,
            Integer latestPublishedEpisodeNumber,
            Integer totalEpisodeCount,
            List<PublicScreenEntryDTO> entries,
            List<PublicScreenFranchiseDTO> franchises
    ) {
        this(
                id,
                slug,
                title,
                originalTitle,
                synopsis,
                structure,
                presentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                publishedAt,
                latestPublishedEpisodeNumber,
                totalEpisodeCount,
                entries,
                franchises,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Backward-compatible accessor returning primary/first franchise, or null if empty.
     */
    public PublicScreenFranchiseDTO franchise() {
        return franchises.isEmpty() ? null : franchises.get(0);
    }
}
