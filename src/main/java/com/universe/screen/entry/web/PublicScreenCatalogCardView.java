package com.universe.screen.entry.web;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.screen.application.dto.PublicScreenCatalogItemDTO;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Public catalog card view model for Thymeleaf rendering.
 *
 * <p>Composes application DTO data with the approved Media public delivery capability.
 * Kept strictly in entry/web layer to prevent application layer from depending on foreign modules.
 */
public record PublicScreenCatalogCardView(
        String slug,
        String title,
        String originalTitle,
        String synopsis,
        ScreenStructure structure,
        ScreenPresentation presentation,
        UUID posterMediaAssetId,
        Instant publishedAt,
        Integer latestPublishedEpisodeNumber,
        Integer totalEpisodeCount
) {

    public PublicScreenCatalogCardView(
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            Instant publishedAt
    ) {
        this(slug, title, originalTitle, synopsis, structure, presentation, posterMediaAssetId, publishedAt, null, null);
    }

    public PublicScreenCatalogCardView {
        Objects.requireNonNull(slug, "Slug cannot be null.");
        Objects.requireNonNull(title, "Title cannot be null.");
        Objects.requireNonNull(structure, "Structure cannot be null.");
        Objects.requireNonNull(presentation, "Presentation cannot be null.");
    }

    public static PublicScreenCatalogCardView fromDTO(PublicScreenCatalogItemDTO dto) {
        Objects.requireNonNull(dto, "PublicScreenCatalogItemDTO cannot be null.");
        return new PublicScreenCatalogCardView(
                dto.slug(),
                dto.title(),
                dto.originalTitle(),
                dto.synopsis(),
                dto.structure(),
                dto.presentation(),
                dto.posterMediaAssetId(),
                dto.publishedAt(),
                dto.latestPublishedEpisodeNumber(),
                dto.totalEpisodeCount()
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
     * True only if originalTitle is non-blank and not identical (case-insensitive, trimmed) to title.
     */
    public boolean hasOriginalTitle() {
        if (originalTitle == null || originalTitle.isBlank()) {
            return false;
        }
        return !originalTitle.trim().equalsIgnoreCase(title.trim());
    }

    /**
     * User-facing Vietnamese episode badge for minimal card display.
     * <ul>
     *   <li>For {@link ScreenStructure#FEATURE}: displays "Phim lẻ".</li>
     *   <li>For {@link ScreenStructure#SERIES}:
     *     <ul>
     *       <li>If no published episodes: "Chưa có tập".</li>
     *       <li>If published episodes exist with known planned total M: "Tập N/M".</li>
     *       <li>If published episodes exist with unknown planned total: "Tập N/??".</li>
     *       <li>If inconsistent legacy data where M &lt; N: safely falls back to "Tập N/??".</li>
     *     </ul>
     *   </li>
     * </ul>
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
}
