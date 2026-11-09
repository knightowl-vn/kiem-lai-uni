package com.universe.screen.application.dto;

import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenTimelineMode;
import com.universe.screen.domain.ScreenTimelineTimestampFormat;

import java.time.Instant;
import java.util.UUID;

public record ScreenEntryDTO(
        UUID id,
        UUID titleId,
        String slug,
        ScreenEntryType type,
        Integer number,
        String title,
        String synopsis,
        UUID videoMediaAssetId,
        UUID thumbnailMediaAssetId,
        Long durationSeconds,
        ScreenPublicationStatus status,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        ScreenTimelineMode introMode,
        Long introStartSeconds,
        Long introEndSeconds,
        ScreenTimelineMode outroMode,
        Long outroStartSeconds
) {
    public ScreenEntryDTO(
            UUID id,
            UUID titleId,
            String slug,
            ScreenEntryType type,
            Integer number,
            String title,
            String synopsis,
            UUID videoMediaAssetId,
            UUID thumbnailMediaAssetId,
            Long durationSeconds,
            ScreenPublicationStatus status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                id,
                titleId,
                slug,
                type,
                number,
                title,
                synopsis,
                videoMediaAssetId,
                thumbnailMediaAssetId,
                durationSeconds,
                status,
                publishedAt,
                createdAt,
                updatedAt,
                ScreenTimelineMode.INHERIT,
                null,
                null,
                ScreenTimelineMode.INHERIT,
                null
        );
    }

    public static ScreenEntryDTO from(ScreenEntry entry) {
        if (entry == null) {
            return null;
        }
        return new ScreenEntryDTO(
                entry.getId(),
                entry.getTitleId(),
                entry.getSlug(),
                entry.getType(),
                entry.getNumber(),
                entry.getTitle(),
                entry.getSynopsis(),
                entry.getVideoMediaAssetId(),
                entry.getThumbnailMediaAssetId(),
                entry.getDurationSeconds(),
                entry.getStatus(),
                entry.getPublishedAt(),
                entry.getCreatedAt(),
                entry.getUpdatedAt(),
                entry.getIntroMode(),
                entry.getIntroStartSeconds(),
                entry.getIntroEndSeconds(),
                entry.getOutroMode(),
                entry.getOutroStartSeconds()
        );
    }

    public String formattedIntroStart() {
        return ScreenTimelineTimestampFormat.formatSeconds(introStartSeconds);
    }

    public String formattedIntroEnd() {
        return ScreenTimelineTimestampFormat.formatSeconds(introEndSeconds);
    }

    public String formattedOutroStart() {
        return ScreenTimelineTimestampFormat.formatSeconds(outroStartSeconds);
    }
}
