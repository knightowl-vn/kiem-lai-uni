package com.universe.screen.application.commands;

import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;

import java.util.UUID;

public record UpdateScreenTitleCommand(
        UUID titleId,
        String slug,
        String title,
        String originalTitle,
        String synopsis,
        ScreenStructure structure,
        ScreenPresentation presentation,
        UUID posterMediaAssetId,
        UUID backdropMediaAssetId,
        Integer totalEpisodeCount,
        boolean updateTotalEpisodeCount
) {
    /**
     * Explicit episode metadata update constructor.
     * Passing a non-null integer sets the declared total.
     * Passing null explicitly clears/unsets the declared total for SERIES.
     */
    public UpdateScreenTitleCommand(
            UUID titleId,
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
        this(
                titleId,
                slug,
                title,
                originalTitle,
                synopsis,
                structure,
                presentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                totalEpisodeCount,
                true
        );
    }

    /**
     * Factory method for updating title metadata without modifying existing episode metadata.
     * Prevents accidental erasure of totalEpisodeCount during unrelated metadata updates.
     */
    public static UpdateScreenTitleCommand withoutEpisodeMetadata(
            UUID titleId,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId
    ) {
        return new UpdateScreenTitleCommand(
                titleId,
                slug,
                title,
                originalTitle,
                synopsis,
                structure,
                presentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                null,
                false
        );
    }
}
