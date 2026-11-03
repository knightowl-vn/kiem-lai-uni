package com.universe.screen.application.commands;

import com.universe.screen.domain.ScreenAiringStatus;
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
        boolean updateTotalEpisodeCount,
        boolean updateReleaseMetadata,
        ScreenAiringStatus airingStatus,
        Integer releaseYear,
        String country,
        String originalAuthor,
        String director,
        String productionStudio
) {

    /**
     * Explicit release metadata update constructor.
     * When release metadata parameters are explicitly supplied, updateReleaseMetadata defaults to true.
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
            Integer totalEpisodeCount,
            boolean updateTotalEpisodeCount,
            ScreenAiringStatus airingStatus,
            Integer releaseYear,
            String country,
            String originalAuthor,
            String director,
            String productionStudio
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
                updateTotalEpisodeCount,
                true,
                airingStatus,
                releaseYear,
                country,
                originalAuthor,
                director,
                productionStudio
        );
    }

    /**
     * Legacy constructor where release metadata is omitted.
     * Sets updateReleaseMetadata to false so existing release metadata is safely preserved.
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
            Integer totalEpisodeCount,
            boolean updateTotalEpisodeCount
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
                updateTotalEpisodeCount,
                false,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Explicit episode metadata update constructor (omits release metadata).
     * Passing a non-null integer sets the declared total.
     * Passing null explicitly clears/unsets the declared total for SERIES.
     * Preserves existing release metadata.
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
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Factory method for updating title metadata without modifying existing episode metadata
     * or release metadata. Prevents accidental erasure of both during unrelated metadata updates.
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
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
