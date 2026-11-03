package com.universe.screen.entry.web;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.screen.application.dto.PublicScreenEntryDetailDTO;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenStructure;

import java.util.Objects;
import java.util.UUID;

/**
 * Public entry detail view model for Thymeleaf rendering.
 */
public record PublicScreenEntryDetailView(
        UUID titleId,
        String titleSlug,
        String titleDisplayName,
        ScreenStructure titleStructure,
        UUID entryId,
        String entrySlug,
        ScreenEntryType entryType,
        Integer entryNumber,
        String entryTitle,
        String entrySynopsis,
        Long durationSeconds,
        UUID thumbnailMediaAssetId,
        boolean hasPlayableVideo
) {

    public PublicScreenEntryDetailView {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");
        Objects.requireNonNull(titleSlug, "Title slug cannot be null.");
        Objects.requireNonNull(titleDisplayName, "Title display name cannot be null.");
        Objects.requireNonNull(titleStructure, "Title structure cannot be null.");
        Objects.requireNonNull(entryId, "Entry ID cannot be null.");
        Objects.requireNonNull(entrySlug, "Entry slug cannot be null.");
        Objects.requireNonNull(entryType, "Entry type cannot be null.");
        Objects.requireNonNull(entryTitle, "Entry title cannot be null.");
    }

    public static PublicScreenEntryDetailView fromDTO(PublicScreenEntryDetailDTO dto) {
        Objects.requireNonNull(dto, "PublicScreenEntryDetailDTO cannot be null.");
        return new PublicScreenEntryDetailView(
                dto.titleId(),
                dto.titleSlug(),
                dto.titleDisplayName(),
                dto.titleStructure(),
                dto.entryId(),
                dto.entrySlug(),
                dto.entryType(),
                dto.entryNumber(),
                dto.entryTitle(),
                dto.entrySynopsis(),
                dto.durationSeconds(),
                dto.thumbnailMediaAssetId(),
                dto.hasPlayableVideo()
        );
    }

    public String displayThumbnailUrl() {
        return thumbnailMediaAssetId != null
                ? MediaDeliveryUrlSupport.variantUrl(thumbnailMediaAssetId, 600)
                : null;
    }

    public String fallbackThumbnailUrl() {
        return thumbnailMediaAssetId != null
                ? MediaDeliveryUrlSupport.contentUrl(thumbnailMediaAssetId)
                : null;
    }

    public boolean hasThumbnail() {
        return thumbnailMediaAssetId != null;
    }

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

    public String displayHeading() {
        return switch (entryType) {
            case EPISODE -> entryNumber != null ? "Tập " + entryNumber : "Tập";
            case FEATURE -> "Bản chiếu chính";
            case SPECIAL -> entryNumber != null ? "Ngoại truyện " + entryNumber : "Tập đặc biệt";
            case TRAILER -> entryNumber != null ? "Trailer " + entryNumber : "Trailer";
        };
    }

    public String backToTitleUrl() {
        return "/screen/" + titleSlug;
    }

    public String pageTitle() {
        return displayHeading() + " - " + titleDisplayName + " - Kiếm Lai Universe";
    }
}
