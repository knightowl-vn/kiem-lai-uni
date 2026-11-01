package com.universe.screen.application.commands;

import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;

import java.util.UUID;

public record CreateScreenTitleCommand(
        String slug,
        String title,
        String originalTitle,
        String synopsis,
        ScreenStructure structure,
        ScreenPresentation presentation,
        UUID posterMediaAssetId,
        UUID backdropMediaAssetId,
        Integer totalEpisodeCount
) {
    public CreateScreenTitleCommand(
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId
    ) {
        this(slug, title, originalTitle, synopsis, structure, presentation, posterMediaAssetId, backdropMediaAssetId, null);
    }

    public CreateScreenTitleCommand(
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId
    ) {
        this(null, title, originalTitle, synopsis, structure, presentation, posterMediaAssetId, backdropMediaAssetId, null);
    }
}
