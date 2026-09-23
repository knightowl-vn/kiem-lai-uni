package com.universe.wiki.contracts.dto;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;

import java.time.Instant;
import java.util.UUID;

/**
 * Dữ liệu bài Wiki công khai dành cho người đọc.
 */
public record PublishedWikiArticleDTO(
        UUID id,
        String title,
        String slug,
        String articleType,
        String summary,
        String content,
        Instant publishedAt,
        Instant updatedAt,
        UUID coverMediaAssetId,
        int coverPositionX,
        int coverPositionY
) {

    public PublishedWikiArticleDTO(
            UUID id,
            String title,
            String slug,
            String articleType,
            String summary,
            String content,
            Instant publishedAt,
            Instant updatedAt,
            UUID coverMediaAssetId
    ) {
        this(
                id,
                title,
                slug,
                articleType,
                summary,
                content,
                publishedAt,
                updatedAt,
                coverMediaAssetId,
                50,
                50
        );
    }

    public PublishedWikiArticleDTO(
            UUID id,
            String title,
            String slug,
            String articleType,
            String summary,
            String content,
            Instant publishedAt,
            Instant updatedAt
    ) {
        this(
                id,
                title,
                slug,
                articleType,
                summary,
                content,
                publishedAt,
                updatedAt,
                null,
                50,
                50
        );
    }

    public String displayCoverImageUrl() {
        return coverMediaAssetId != null
                ? MediaDeliveryUrlSupport.variantUrl(coverMediaAssetId, 300)
                : null;
    }

    public String fallbackCoverImageUrl() {
        return coverMediaAssetId != null
                ? MediaDeliveryUrlSupport.contentUrl(coverMediaAssetId)
                : null;
    }

    public String coverObjectPosition() {
        return coverPositionX + "% " + coverPositionY + "%";
    }
}