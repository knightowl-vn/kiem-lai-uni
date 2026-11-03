package com.universe.screen.entry.web;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.screen.application.dto.PublicScreenEntryDTO;
import com.universe.screen.domain.ScreenEntryType;

import java.util.Objects;
import java.util.UUID;

/**
 * Public entry view model for Thymeleaf rendering.
 *
 * <p>Composes application entry DTO data with the approved Media public delivery capability.
 */
public record PublicScreenEntryView(
        UUID id,
        String slug,
        ScreenEntryType type,
        Integer number,
        String title,
        String synopsis,
        Long durationSeconds,
        UUID thumbnailMediaAssetId,
        boolean hasPlayableVideo
) {

    public PublicScreenEntryView {
        Objects.requireNonNull(id, "Entry ID cannot be null.");
        Objects.requireNonNull(slug, "Slug cannot be null.");
        Objects.requireNonNull(type, "ScreenEntryType cannot be null.");
        Objects.requireNonNull(title, "Title cannot be null.");
    }

    public static PublicScreenEntryView fromDTO(PublicScreenEntryDTO dto) {
        Objects.requireNonNull(dto, "PublicScreenEntryDTO cannot be null.");
        return new PublicScreenEntryView(
                dto.id(),
                dto.slug(),
                dto.type(),
                dto.number(),
                dto.title(),
                dto.synopsis(),
                dto.durationSeconds(),
                dto.thumbnailMediaAssetId(),
                dto.hasPlayableVideo()
        );
    }

    /**
     * Resolves the canonical 300px image variant delivery URL for thumbnail.
     */
    public String displayThumbnailUrl() {
        return thumbnailMediaAssetId != null
                ? MediaDeliveryUrlSupport.variantUrl(thumbnailMediaAssetId, 300)
                : null;
    }

    /**
     * Resolves the fallback canonical content URL for thumbnail.
     */
    public String fallbackThumbnailUrl() {
        return thumbnailMediaAssetId != null
                ? MediaDeliveryUrlSupport.contentUrl(thumbnailMediaAssetId)
                : null;
    }

    /**
     * Formats duration in seconds to standard mm:ss or hh:mm:ss.
     */
    public String formattedDuration() {
        if (durationSeconds == null || durationSeconds <= 0) {
            return null;
        }
        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        long seconds = durationSeconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    /**
     * User-facing primary heading (e.g. "Tập 1", "Bản chiếu chính", "Trailer 1").
     */
    public String displayHeading() {
        return switch (type) {
            case EPISODE -> number != null ? "Tập " + number : "Tập";
            case FEATURE -> "Bản chiếu chính";
            case SPECIAL -> number != null ? "Ngoại truyện " + number : "Tập đặc biệt";
            case TRAILER -> number != null ? "Trailer " + number : "Trailer";
        };
    }

    /**
     * Vietnamese display label for entry type badge.
     */
    public String typeBadgeText() {
        return switch (type) {
            case EPISODE -> "Tập phim";
            case FEATURE -> "Phim lẻ";
            case SPECIAL -> "Ngoại truyện";
            case TRAILER -> "Trailer";
        };
    }

    /**
     * True if the entry has a custom title distinct from its default display heading.
     */
    public boolean hasDistinctTitle() {
        if (title == null || title.isBlank()) {
            return false;
        }
        return !title.trim().equalsIgnoreCase(displayHeading().trim());
    }
}
