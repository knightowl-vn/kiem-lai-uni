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
        String productionStudio,
        boolean updateDefaultTimeline,
        Long defaultIntroStartSeconds,
        Long defaultIntroEndSeconds,
        Long defaultOutroStartSeconds,
        boolean updateDefaultOutroEnd,
        Long defaultOutroEndSeconds
) {

    /**
     * Explicit release metadata and default timeline update constructor including outro end boundary.
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
            String productionStudio,
            Long defaultIntroStartSeconds,
            Long defaultIntroEndSeconds,
            Long defaultOutroStartSeconds,
            Long defaultOutroEndSeconds
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
                productionStudio,
                true,
                defaultIntroStartSeconds,
                defaultIntroEndSeconds,
                defaultOutroStartSeconds,
                true,
                defaultOutroEndSeconds
        );
    }

    /**
     * Explicit release metadata and default timeline update constructor (without outro end boundary).
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
            String productionStudio,
            Long defaultIntroStartSeconds,
            Long defaultIntroEndSeconds,
            Long defaultOutroStartSeconds
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
                productionStudio,
                true,
                defaultIntroStartSeconds,
                defaultIntroEndSeconds,
                defaultOutroStartSeconds,
                false,
                null
        );
    }

    /**
     * 23-parameter constructor with explicit updateDefaultTimeline flag and outro end boundary.
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
            boolean updateReleaseMetadata,
            ScreenAiringStatus airingStatus,
            Integer releaseYear,
            String country,
            String originalAuthor,
            String director,
            String productionStudio,
            boolean updateDefaultTimeline,
            Long defaultIntroStartSeconds,
            Long defaultIntroEndSeconds,
            Long defaultOutroStartSeconds,
            Long defaultOutroEndSeconds
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
                updateReleaseMetadata,
                airingStatus,
                releaseYear,
                country,
                originalAuthor,
                director,
                productionStudio,
                updateDefaultTimeline,
                defaultIntroStartSeconds,
                defaultIntroEndSeconds,
                defaultOutroStartSeconds,
                true,
                defaultOutroEndSeconds
        );
    }

    /**
     * Backward-compatible 22-parameter constructor with explicit updateDefaultTimeline flag
     * (without outro end boundary).
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
            boolean updateReleaseMetadata,
            ScreenAiringStatus airingStatus,
            Integer releaseYear,
            String country,
            String originalAuthor,
            String director,
            String productionStudio,
            boolean updateDefaultTimeline,
            Long defaultIntroStartSeconds,
            Long defaultIntroEndSeconds,
            Long defaultOutroStartSeconds
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
                updateReleaseMetadata,
                airingStatus,
                releaseYear,
                country,
                originalAuthor,
                director,
                productionStudio,
                updateDefaultTimeline,
                defaultIntroStartSeconds,
                defaultIntroEndSeconds,
                defaultOutroStartSeconds,
                false,
                null
        );
    }

    /**
     * Legacy 18-parameter constructor where default timeline metadata is omitted.
     * Preserves existing default timeline metadata.
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
            boolean updateReleaseMetadata,
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
                updateReleaseMetadata,
                airingStatus,
                releaseYear,
                country,
                originalAuthor,
                director,
                productionStudio,
                false,
                null,
                null,
                null,
                false,
                null
        );
    }

    /**
     * Explicit release metadata update constructor (omits default timeline).
     * When release metadata parameters are explicitly supplied, updateReleaseMetadata defaults to true,
     * while existing default timeline metadata is preserved.
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
                productionStudio,
                false,
                null,
                null,
                null,
                false,
                null
        );
    }

    /**
     * Legacy constructor where release metadata and default timeline are omitted.
     * Sets updateReleaseMetadata and updateDefaultTimeline to false so existing metadata is safely preserved.
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
                null,
                false,
                null,
                null,
                null,
                false,
                null
        );
    }

    /**
     * Explicit episode metadata update constructor (omits release metadata and default timeline).
     * Passing a non-null integer sets the declared total.
     * Passing null explicitly clears/unsets the declared total for SERIES.
     * Preserves existing release metadata and default timeline metadata.
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
                null,
                false,
                null,
                null,
                null,
                false,
                null
        );
    }

    /**
     * Factory method for updating title metadata without modifying existing episode metadata,
     * release metadata, or default timeline metadata. Prevents accidental erasure during unrelated updates.
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
                null,
                false,
                null,
                null,
                null,
                false,
                null
        );
    }
}
