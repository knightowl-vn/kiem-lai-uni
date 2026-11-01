package com.universe.screen.application.dto;

import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Public catalog application DTO representing one published Screen title.
 *
 * <p>Contains only client-safe metadata approved for public browsing.
 * Kept completely decoupled from outer web/view composition and foreign module types.
 */
public record PublicScreenCatalogItemDTO(
        String slug,
        String title,
        String originalTitle,
        String synopsis,
        ScreenStructure structure,
        ScreenPresentation presentation,
        UUID posterMediaAssetId,
        Instant publishedAt,
        Integer latestPublishedEpisodeNumber,
        Integer totalEpisodeCount
) {

    public PublicScreenCatalogItemDTO(
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            Instant publishedAt
    ) {
        this(slug, title, originalTitle, synopsis, structure, presentation, posterMediaAssetId, publishedAt, null, null);
    }

    public PublicScreenCatalogItemDTO {
        Objects.requireNonNull(slug, "Slug cannot be null.");
        Objects.requireNonNull(title, "Title cannot be null.");
        Objects.requireNonNull(structure, "Structure cannot be null.");
        Objects.requireNonNull(presentation, "Presentation cannot be null.");
    }
}
