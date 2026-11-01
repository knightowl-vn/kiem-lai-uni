package com.universe.screen.application.dto;

import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;

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
        Integer totalEpisodeCount
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
                null
        );
    }
}
