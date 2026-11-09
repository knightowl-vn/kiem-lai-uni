package com.universe.screen.application.dto;

import com.universe.screen.domain.ScreenAiringStatus;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;

import java.time.Instant;
import java.util.UUID;

public record ScreenTitleDTO(
        UUID id,
        String slug,
        String title,
        String originalTitle,
        String synopsis,
        ScreenStructure structure,
        ScreenPresentation presentation,
        UUID posterMediaAssetId,
        UUID backdropMediaAssetId,
        ScreenPublicationStatus status,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        Integer totalEpisodeCount,
        ScreenAiringStatus airingStatus,
        Integer releaseYear,
        String country,
        String originalAuthor,
        String director,
        String productionStudio,
        Long defaultIntroStartSeconds,
        Long defaultIntroEndSeconds,
        Long defaultOutroStartSeconds
) {
    public ScreenTitleDTO(
            UUID id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            ScreenPublicationStatus status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            Integer totalEpisodeCount,
            ScreenAiringStatus airingStatus,
            Integer releaseYear,
            String country,
            String originalAuthor,
            String director,
            String productionStudio
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
                status,
                publishedAt,
                createdAt,
                updatedAt,
                totalEpisodeCount,
                airingStatus,
                releaseYear,
                country,
                originalAuthor,
                director,
                productionStudio,
                null,
                null,
                null
        );
    }

    public ScreenTitleDTO(
            UUID id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            ScreenPublicationStatus status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            Integer totalEpisodeCount
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
                status,
                publishedAt,
                createdAt,
                updatedAt,
                totalEpisodeCount,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public ScreenTitleDTO(
            UUID id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            ScreenPublicationStatus status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt
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
                status,
                publishedAt,
                createdAt,
                updatedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static ScreenTitleDTO from(ScreenTitle title) {
        if (title == null) {
            return null;
        }
        return new ScreenTitleDTO(
                title.getId(),
                title.getSlug(),
                title.getTitle(),
                title.getOriginalTitle(),
                title.getSynopsis(),
                title.getStructure(),
                title.getPresentation(),
                title.getPosterMediaAssetId(),
                title.getBackdropMediaAssetId(),
                title.getStatus(),
                title.getPublishedAt(),
                title.getCreatedAt(),
                title.getUpdatedAt(),
                title.getTotalEpisodeCount(),
                title.getAiringStatus(),
                title.getReleaseYear(),
                title.getCountry(),
                title.getOriginalAuthor(),
                title.getDirector(),
                title.getProductionStudio(),
                title.getDefaultIntroStartSeconds(),
                title.getDefaultIntroEndSeconds(),
                title.getDefaultOutroStartSeconds()
        );
    }
}
