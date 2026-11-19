package com.universe.wiki.entry.web;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * View model representing one published Wiki article card for public index rendering.
 *
 * <p>Composes application DTO data with pre-resolved Media direct delivery URLs.
 * When direct delivery is unavailable or ineligible, falls back to canonical {@code /content}
 * (never an unverified {@code /variants/w300} URL).
 */
public record PublicWikiArticleCardView(
        UUID id,
        String title,
        String slug,
        String articleType,
        String summary,
        Instant publishedAt,
        Instant updatedAt,
        UUID coverMediaAssetId,
        int coverPositionX,
        int coverPositionY,
        String directCoverUrl
) {

    public static PublicWikiArticleCardView fromDTO(PublishedWikiArticleListItemDTO dto, String directCoverUrl) {
        Objects.requireNonNull(dto, "PublishedWikiArticleListItemDTO cannot be null.");
        return new PublicWikiArticleCardView(
                dto.id(),
                dto.title(),
                dto.slug(),
                dto.articleType(),
                dto.summary(),
                dto.publishedAt(),
                dto.updatedAt(),
                dto.coverMediaAssetId(),
                dto.coverPositionX(),
                dto.coverPositionY(),
                directCoverUrl
        );
    }

    /**
     * Resolves the primary image delivery URL for the cover:
     * - Returns accelerated direct Cloudinary URL if available;
     * - Else falls back to canonical Media content delivery URL if present (avoids unverified variant requests);
     * - Else returns null.
     */
    public String displayCoverImageUrl() {
        if (directCoverUrl != null && !directCoverUrl.isBlank()) {
            return directCoverUrl;
        }
        return fallbackCoverImageUrl();
    }

    /**
     * Resolves the fallback canonical content URL for the cover.
     */
    public String fallbackCoverImageUrl() {
        return coverMediaAssetId != null
                ? MediaDeliveryUrlSupport.contentUrl(coverMediaAssetId)
                : null;
    }

    /**
     * Formats CSS object-position for cover image alignment.
     */
    public String coverObjectPosition() {
        return coverPositionX + "% " + coverPositionY + "%";
    }
}
