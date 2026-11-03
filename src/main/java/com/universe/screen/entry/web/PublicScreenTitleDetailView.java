package com.universe.screen.entry.web;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.screen.application.dto.PublicScreenFranchiseDTO;
import com.universe.screen.application.dto.PublicScreenTitleDetailDTO;
import com.universe.screen.domain.ScreenAiringStatus;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Public title detail view model for Thymeleaf rendering.
 *
 * <p>Composes application DTO data with the approved Media public delivery capability
 * and presentation formatting methods.
 */
public record PublicScreenTitleDetailView(
        UUID id,
        String slug,
        String title,
        String originalTitle,
        String synopsis,
        ScreenStructure structure,
        ScreenPresentation presentation,
        UUID posterMediaAssetId,
        UUID backdropMediaAssetId,
        Instant publishedAt,
        Integer latestPublishedEpisodeNumber,
        Integer totalEpisodeCount,
        List<PublicScreenEntryView> entries,
        List<PublicScreenFranchiseDTO> franchises,
        ScreenAiringStatus airingStatus,
        Integer releaseYear,
        String country,
        String originalAuthor,
        String director,
        String productionStudio,
        Integer averageDurationMinutes
) {

    public PublicScreenTitleDetailView {
        Objects.requireNonNull(id, "Title ID cannot be null.");
        Objects.requireNonNull(slug, "Slug cannot be null.");
        Objects.requireNonNull(title, "Title cannot be null.");
        Objects.requireNonNull(structure, "ScreenStructure cannot be null.");
        Objects.requireNonNull(presentation, "ScreenPresentation cannot be null.");
        entries = entries == null ? List.of() : List.copyOf(entries);
        franchises = franchises == null ? List.of() : List.copyOf(franchises);
    }

    /**
     * Backward-compatible constructor without extended release metadata.
     */
    public PublicScreenTitleDetailView(
            UUID id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            Instant publishedAt,
            Integer latestPublishedEpisodeNumber,
            Integer totalEpisodeCount,
            List<PublicScreenEntryView> entries,
            List<PublicScreenFranchiseDTO> franchises
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
                publishedAt,
                latestPublishedEpisodeNumber,
                totalEpisodeCount,
                entries,
                franchises,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static PublicScreenTitleDetailView fromDTO(PublicScreenTitleDetailDTO dto) {
        Objects.requireNonNull(dto, "PublicScreenTitleDetailDTO cannot be null.");
        List<PublicScreenEntryView> entryViews = dto.entries().stream()
                .map(PublicScreenEntryView::fromDTO)
                .toList();

        return new PublicScreenTitleDetailView(
                dto.id(),
                dto.slug(),
                dto.title(),
                dto.originalTitle(),
                dto.synopsis(),
                dto.structure(),
                dto.presentation(),
                dto.posterMediaAssetId(),
                dto.backdropMediaAssetId(),
                dto.publishedAt(),
                dto.latestPublishedEpisodeNumber(),
                dto.totalEpisodeCount(),
                entryViews,
                dto.franchises(),
                dto.airingStatus(),
                dto.releaseYear(),
                dto.country(),
                dto.originalAuthor(),
                dto.director(),
                dto.productionStudio(),
                dto.averageDurationMinutes()
        );
    }

    /**
     * Resolves the canonical 300px image variant delivery URL for the poster.
     */
    public String displayPosterUrl() {
        return posterMediaAssetId != null
                ? MediaDeliveryUrlSupport.variantUrl(posterMediaAssetId, 300)
                : null;
    }

    /**
     * Resolves the fallback canonical content URL for the poster.
     */
    public String fallbackPosterUrl() {
        return posterMediaAssetId != null
                ? MediaDeliveryUrlSupport.contentUrl(posterMediaAssetId)
                : null;
    }

    /**
     * Resolves the 1200px variant delivery URL for the backdrop image when present.
     */
    public String displayBackdropUrl() {
        return backdropMediaAssetId != null
                ? MediaDeliveryUrlSupport.variantUrl(backdropMediaAssetId, 1200)
                : null;
    }

    /**
     * Resolves the fallback canonical content URL for the backdrop image.
     */
    public String fallbackBackdropUrl() {
        return backdropMediaAssetId != null
                ? MediaDeliveryUrlSupport.contentUrl(backdropMediaAssetId)
                : null;
    }

    public boolean hasBackdrop() {
        return backdropMediaAssetId != null;
    }

    /**
     * Plain secondary classification text combining structure and presentation.
     * e.g., "Phim bộ · Hoạt hình 3D" or "Phim lẻ · Người đóng".
     */
    public String structureAndPresentationText() {
        return structureDisplayName() + " · " + presentationDisplayName();
    }

    /**
     * Resolves the earliest eligible entry for "Xem ngay" (Watch Now) navigation:
     * - For SERIES: First published EPISODE in canonical ascending order;
     * - For FEATURE: Published main FEATURE entry;
     * - Returns null if no eligible entry is published.
     */
    public PublicScreenEntryView firstEligibleWatchEntry() {
        if (structure == ScreenStructure.SERIES) {
            return entries.stream()
                    .filter(e -> e.type() == ScreenEntryType.EPISODE)
                    .findFirst()
                    .orElse(null);
        } else if (structure == ScreenStructure.FEATURE) {
            return entries.stream()
                    .filter(e -> e.type() == ScreenEntryType.FEATURE)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    public boolean hasEligibleWatchEntry() {
        return firstEligibleWatchEntry() != null;
    }

    public String watchNowUrl() {
        PublicScreenEntryView first = firstEligibleWatchEntry();
        return first != null ? "/screen/" + slug + "/entries/" + first.slug() : null;
    }

    /**
     * User-facing Vietnamese display label for ScreenStructure.
     */
    public String structureDisplayName() {
        return switch (structure) {
            case SERIES -> "Phim bộ";
            case FEATURE -> "Phim lẻ";
        };
    }

    /**
     * User-facing Vietnamese display label for ScreenPresentation.
     */
    public String presentationDisplayName() {
        return switch (presentation) {
            case ANIMATION_3D -> "Hoạt hình 3D";
            case LIVE_ACTION -> "Người đóng";
        };
    }

    /**
     * Determines whether original title should be displayed beneath the display title.
     */
    public boolean hasOriginalTitle() {
        if (originalTitle == null || originalTitle.isBlank()) {
            return false;
        }
        return !originalTitle.trim().equalsIgnoreCase(title.trim());
    }

    public boolean hasEpisodeProgressBadge() {
        return structure == ScreenStructure.SERIES;
    }

    /**
     * User-facing Vietnamese episode badge.
     */
    public String episodeBadgeText() {
        if (structure == ScreenStructure.FEATURE) {
            return "Phim lẻ";
        }
        if (latestPublishedEpisodeNumber == null || latestPublishedEpisodeNumber <= 0) {
            return "Chưa có tập";
        }
        if (totalEpisodeCount != null && totalEpisodeCount < latestPublishedEpisodeNumber) {
            return "Tập " + latestPublishedEpisodeNumber + "/??";
        }
        String totalPart = (totalEpisodeCount != null && totalEpisodeCount > 0)
                ? String.valueOf(totalEpisodeCount)
                : "??";
        return "Tập " + latestPublishedEpisodeNumber + "/" + totalPart;
    }

    public boolean hasAiringStatus() {
        return airingStatus != null;
    }

    public String airingStatusDisplayName() {
        return airingStatus != null ? airingStatus.getDisplayName() : null;
    }

    public boolean hasDuration() {
        return averageDurationMinutes != null && averageDurationMinutes > 0;
    }

    public String durationLabel() {
        return structure == ScreenStructure.FEATURE ? "Thời lượng" : "Thời lượng";
    }

    public String formattedDuration() {
        if (!hasDuration()) {
            return null;
        }
        if (structure == ScreenStructure.FEATURE) {
            return averageDurationMinutes + " phút";
        }
        return "~" + averageDurationMinutes + " phút/tập";
    }

    public boolean hasReleaseYear() {
        return releaseYear != null;
    }

    public boolean hasCountry() {
        return country != null && !country.isBlank();
    }

    public boolean hasOriginalAuthor() {
        return originalAuthor != null && !originalAuthor.isBlank();
    }

    public boolean hasDirector() {
        return director != null && !director.isBlank();
    }

    public boolean hasProductionStudio() {
        return productionStudio != null && !productionStudio.isBlank();
    }

    public boolean hasDetailedMetadata() {
        return hasDuration() || hasCountry() || hasOriginalAuthor() || hasReleaseYear() || hasDirector() || hasProductionStudio();
    }

    public boolean hasMetadata() {
        return hasDuration() || hasReleaseYear() || hasCountry() || hasOriginalAuthor() || hasDirector() || hasProductionStudio();
    }

    public boolean hasEntries() {
        return !entries.isEmpty();
    }

    public boolean hasFranchises() {
        return !franchises.isEmpty();
    }

    public boolean hasFranchise() {
        return !franchises.isEmpty();
    }

    public PublicScreenFranchiseDTO franchise() {
        return franchises.isEmpty() ? null : franchises.get(0);
    }

    public List<PublicScreenEntryView> episodes() {
        return entries.stream()
                .filter(e -> e.type() == ScreenEntryType.EPISODE)
                .toList();
    }

    public List<PublicScreenEntryView> features() {
        return entries.stream()
                .filter(e -> e.type() == ScreenEntryType.FEATURE)
                .toList();
    }

    public List<PublicScreenEntryView> specials() {
        return entries.stream()
                .filter(e -> e.type() == ScreenEntryType.SPECIAL)
                .toList();
    }

    public List<PublicScreenEntryView> trailers() {
        return entries.stream()
                .filter(e -> e.type() == ScreenEntryType.TRAILER)
                .toList();
    }
}
