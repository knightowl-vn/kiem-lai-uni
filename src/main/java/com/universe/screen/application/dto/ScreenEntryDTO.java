package com.universe.screen.application.dto;

import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPublicationStatus;

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
        Instant updatedAt
) {
}
