package com.universe.screen.entry.web;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.screen.application.dto.PublicScreenWatchDTO;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTimelineTimestampFormat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Public watch view model for Thymeleaf SSR rendering.
 *
 * <p>Composes watch metadata, player readiness, deterministic previous/next navigation,
 * and entry playlist.
 */
public record PublicScreenWatchView(
        UUID titleId,
        String titleSlug,
        String titleDisplayName,
        ScreenStructure titleStructure,
        ScreenPresentation titlePresentation,
        UUID posterMediaAssetId,
        UUID backdropMediaAssetId,
        UUID entryId,
        String entrySlug,
        ScreenEntryType entryType,
        Integer entryNumber,
        String entryTitle,
        String entrySynopsis,
        Long durationSeconds,
        UUID thumbnailMediaAssetId,
        boolean isPlayable,
        String videoDeliveryUrl,
        String videoMimeType,
        PublicScreenEntryView previousEntry,
        PublicScreenEntryView nextEntry,
        List<PublicScreenEntryView> playlist,
        Long effectiveIntroStartSeconds,
        Long effectiveIntroEndSeconds,
        boolean hasEffectiveIntro,
        Long effectiveOutroStartSeconds,
        Long effectiveOutroEndSeconds,
        boolean hasEffectiveOutro
) {

    public PublicScreenWatchView {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");
        Objects.requireNonNull(titleSlug, "Title slug cannot be null.");
        Objects.requireNonNull(titleDisplayName, "Title display name cannot be null.");
        Objects.requireNonNull(titleStructure, "Title structure cannot be null.");
        Objects.requireNonNull(titlePresentation, "Title presentation cannot be null.");
        Objects.requireNonNull(entryId, "Entry ID cannot be null.");
        Objects.requireNonNull(entrySlug, "Entry slug cannot be null.");
        Objects.requireNonNull(entryType, "Entry type cannot be null.");
        Objects.requireNonNull(entryTitle, "Entry title cannot be null.");
        playlist = playlist != null ? List.copyOf(playlist) : List.of();
    }

    /**
     * Backward-compatible convenience constructor omitting timeline metadata.
     */
    public PublicScreenWatchView(
            UUID titleId,
            String titleSlug,
            String titleDisplayName,
            ScreenStructure titleStructure,
            ScreenPresentation titlePresentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            UUID entryId,
            String entrySlug,
            ScreenEntryType entryType,
            Integer entryNumber,
            String entryTitle,
            String entrySynopsis,
            Long durationSeconds,
            UUID thumbnailMediaAssetId,
            boolean isPlayable,
            String videoDeliveryUrl,
            String videoMimeType,
            PublicScreenEntryView previousEntry,
            PublicScreenEntryView nextEntry,
            List<PublicScreenEntryView> playlist
    ) {
        this(
                titleId,
                titleSlug,
                titleDisplayName,
                titleStructure,
                titlePresentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                entryId,
                entrySlug,
                entryType,
                entryNumber,
                entryTitle,
                entrySynopsis,
                durationSeconds,
                thumbnailMediaAssetId,
                isPlayable,
                videoDeliveryUrl,
                videoMimeType,
                previousEntry,
                nextEntry,
                playlist,
                null,
                null,
                false,
                null,
                null,
                false
        );
    }

    /**
     * Backward-compatible convenience constructor omitting title backdropMediaAssetId.
     */
    public PublicScreenWatchView(
            UUID titleId,
            String titleSlug,
            String titleDisplayName,
            ScreenStructure titleStructure,
            ScreenPresentation titlePresentation,
            UUID posterMediaAssetId,
            UUID entryId,
            String entrySlug,
            ScreenEntryType entryType,
            Integer entryNumber,
            String entryTitle,
            String entrySynopsis,
            Long durationSeconds,
            UUID thumbnailMediaAssetId,
            boolean isPlayable,
            String videoDeliveryUrl,
            String videoMimeType,
            PublicScreenEntryView previousEntry,
            PublicScreenEntryView nextEntry,
            List<PublicScreenEntryView> playlist
    ) {
        this(
                titleId,
                titleSlug,
                titleDisplayName,
                titleStructure,
                titlePresentation,
                posterMediaAssetId,
                null,
                entryId,
                entrySlug,
                entryType,
                entryNumber,
                entryTitle,
                entrySynopsis,
                durationSeconds,
                thumbnailMediaAssetId,
                isPlayable,
                videoDeliveryUrl,
                videoMimeType,
                previousEntry,
                nextEntry,
                playlist
        );
    }

    public static PublicScreenWatchView fromDTO(PublicScreenWatchDTO dto) {
        Objects.requireNonNull(dto, "PublicScreenWatchDTO cannot be null.");

        PublicScreenEntryView prevView = dto.previousEntry() != null
                ? PublicScreenEntryView.fromDTO(dto.previousEntry())
                : null;

        PublicScreenEntryView nextView = dto.nextEntry() != null
                ? PublicScreenEntryView.fromDTO(dto.nextEntry())
                : null;

        List<PublicScreenEntryView> playlistViews = dto.playlist().stream()
                .map(PublicScreenEntryView::fromDTO)
                .toList();

        return new PublicScreenWatchView(
                dto.titleId(),
                dto.titleSlug(),
                dto.titleDisplayName(),
                dto.titleStructure(),
                dto.titlePresentation(),
                dto.posterMediaAssetId(),
                dto.backdropMediaAssetId(),
                dto.entryId(),
                dto.entrySlug(),
                dto.entryType(),
                dto.entryNumber(),
                dto.entryTitle(),
                dto.entrySynopsis(),
                dto.durationSeconds(),
                dto.thumbnailMediaAssetId(),
                dto.isPlayable(),
                dto.videoDeliveryUrl(),
                dto.videoMimeType(),
                prevView,
                nextView,
                playlistViews,
                dto.effectiveIntroStartSeconds(),
                dto.effectiveIntroEndSeconds(),
                dto.hasEffectiveIntro(),
                dto.effectiveOutroStartSeconds(),
                dto.effectiveOutroEndSeconds(),
                dto.hasEffectiveOutro()
        );
    }

    public boolean hasEffectiveIntro() {
        return hasEffectiveIntro;
    }

    public boolean hasEffectiveOutro() {
        return hasEffectiveOutro;
    }

    public boolean hasBoundedEffectiveOutro() {
        return hasEffectiveOutro && effectiveOutroStartSeconds != null && effectiveOutroEndSeconds != null;
    }

    public String formattedEffectiveIntroStart() {
        return ScreenTimelineTimestampFormat.formatSeconds(effectiveIntroStartSeconds);
    }

    public String formattedEffectiveIntroEnd() {
        return ScreenTimelineTimestampFormat.formatSeconds(effectiveIntroEndSeconds);
    }

    public String formattedEffectiveOutroStart() {
        return ScreenTimelineTimestampFormat.formatSeconds(effectiveOutroStartSeconds);
    }

    public String formattedEffectiveOutroEnd() {
        return ScreenTimelineTimestampFormat.formatSeconds(effectiveOutroEndSeconds);
    }

    public boolean isEpisode() {
        return entryType == ScreenEntryType.EPISODE;
    }

    public boolean hasVideo() {
        return isPlayable && videoDeliveryUrl != null && !videoDeliveryUrl.isBlank();
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

    public String displayPosterUrl() {
        return posterMediaAssetId != null
                ? MediaDeliveryUrlSupport.variantUrl(posterMediaAssetId, 300)
                : null;
    }

    public String fallbackPosterUrl() {
        return posterMediaAssetId != null
                ? MediaDeliveryUrlSupport.contentUrl(posterMediaAssetId)
                : null;
    }

    public String displayBackdropUrl() {
        return backdropMediaAssetId != null
                ? MediaDeliveryUrlSupport.variantUrl(backdropMediaAssetId, 1200)
                : null;
    }

    public String fallbackBackdropUrl() {
        return backdropMediaAssetId != null
                ? MediaDeliveryUrlSupport.contentUrl(backdropMediaAssetId)
                : null;
    }

    /**
     * Resolves the primary visual stage image for player poster and stage background:
     * - Returns episode thumbnail (variant w600) if present;
     * - Else falls back to title landscape backdrop (variant w1200) if present;
     * - Else falls back to title portrait poster (variant w600) if present;
     * - Else returns null.
     */
    public String displayStageImageUrl() {
        if (thumbnailMediaAssetId != null) {
            return MediaDeliveryUrlSupport.variantUrl(thumbnailMediaAssetId, 600);
        }
        if (backdropMediaAssetId != null) {
            return MediaDeliveryUrlSupport.variantUrl(backdropMediaAssetId, 1200);
        }
        if (posterMediaAssetId != null) {
            return MediaDeliveryUrlSupport.variantUrl(posterMediaAssetId, 600);
        }
        return null;
    }

    /**
     * Resolves the fallback canonical content URL for the stage image.
     */
    public String fallbackStageImageUrl() {
        if (thumbnailMediaAssetId != null) {
            return MediaDeliveryUrlSupport.contentUrl(thumbnailMediaAssetId);
        }
        if (backdropMediaAssetId != null) {
            return MediaDeliveryUrlSupport.contentUrl(backdropMediaAssetId);
        }
        if (posterMediaAssetId != null) {
            return MediaDeliveryUrlSupport.contentUrl(posterMediaAssetId);
        }
        return null;
    }

    /**
     * Resolves the secondary fallback canonical content URL for the stage image
     * when both the primary stage image and its direct content fallback fail.
     */
    public String secondaryFallbackStageImageUrl() {
        if (thumbnailMediaAssetId != null) {
            if (backdropMediaAssetId != null) {
                return MediaDeliveryUrlSupport.contentUrl(backdropMediaAssetId);
            }
            if (posterMediaAssetId != null) {
                return MediaDeliveryUrlSupport.contentUrl(posterMediaAssetId);
            }
            return null;
        }
        if (backdropMediaAssetId != null && posterMediaAssetId != null) {
            return MediaDeliveryUrlSupport.contentUrl(posterMediaAssetId);
        }
        return null;
    }

    /**
     * Returns true if any visual image (episode thumbnail, title backdrop, or title poster)
     * is available for the visual stage.
     */
    public boolean hasStageImage() {
        return thumbnailMediaAssetId != null || backdropMediaAssetId != null || posterMediaAssetId != null;
    }

    /**
     * Returns true if the selected stage image is portrait (i.e. only title poster is available,
     * without episode thumbnail or title landscape backdrop).
     */
    public boolean isStageImagePortrait() {
        return thumbnailMediaAssetId == null && backdropMediaAssetId == null && posterMediaAssetId != null;
    }

    /**
     * Returns true if the selected stage image is landscape (either episode thumbnail or title backdrop).
     */
    public boolean isStageImageLandscape() {
        return thumbnailMediaAssetId != null || backdropMediaAssetId != null;
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

    public String typeBadgeText() {
        return switch (entryType) {
            case EPISODE -> "Tập phim";
            case FEATURE -> "Phim lẻ";
            case SPECIAL -> "Ngoại truyện";
            case TRAILER -> "Trailer";
        };
    }

    /**
     * Resolves the compact now-playing heading displayed immediately above the media stage.
     * E.g.: "Kiếm Lai Phần 1 · Tập 1" or "Kiếm Lai Điện Ảnh · Bản chiếu chính".
     */
    public String nowPlayingHeading() {
        String entryLabel = displayHeading();
        if (titleDisplayName == null || titleDisplayName.isBlank()) {
            return entryLabel != null ? entryLabel : "";
        }
        if (entryLabel == null || entryLabel.isBlank()) {
            return titleDisplayName;
        }
        if (entryLabel.startsWith(titleDisplayName)) {
            return entryLabel;
        }
        return titleDisplayName + " · " + entryLabel;
    }

    public String pageTitle() {
        return displayHeading() + " - " + titleDisplayName + " - Kiếm Lai Universe";
    }

    public String backToTitleUrl() {
        return "/screen/" + titleSlug;
    }

    public String entryDetailUrl() {
        return "/screen/" + titleSlug + "/entries/" + entrySlug;
    }

    public boolean hasPrevious() {
        return previousEntry != null;
    }

    public boolean hasNext() {
        return nextEntry != null;
    }

    public String previousWatchUrl() {
        return previousEntry != null ? "/screen/" + titleSlug + "/watch/" + previousEntry.slug() : null;
    }

    public String nextWatchUrl() {
        return nextEntry != null ? "/screen/" + titleSlug + "/watch/" + nextEntry.slug() : null;
    }

    public String watchUrlFor(String otherEntrySlug) {
        if (otherEntrySlug == null || otherEntrySlug.isBlank()) {
            return null;
        }
        return "/screen/" + titleSlug + "/watch/" + otherEntrySlug.trim();
    }

    public boolean isCurrent(UUID candidateId) {
        return entryId != null && entryId.equals(candidateId);
    }

    public List<PublicScreenEntryView> episodes() {
        return playlist.stream()
                .filter(e -> e.type() == ScreenEntryType.EPISODE)
                .toList();
    }

    public List<PublicScreenEntryView> specials() {
        return playlist.stream()
                .filter(e -> e.type() == ScreenEntryType.SPECIAL)
                .toList();
    }

    public List<PublicScreenEntryView> trailers() {
        return playlist.stream()
                .filter(e -> e.type() == ScreenEntryType.TRAILER)
                .toList();
    }

    public List<PublicScreenEntryView> features() {
        return playlist.stream()
                .filter(e -> e.type() == ScreenEntryType.FEATURE)
                .toList();
    }

    public boolean hasDistinctTitle() {
        if (entryTitle == null || entryTitle.isBlank()) {
            return false;
        }
        return !entryTitle.trim().equalsIgnoreCase(displayHeading().trim());
    }
}
