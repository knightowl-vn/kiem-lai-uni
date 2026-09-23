package com.universe.wiki.contracts.dto;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;

import java.time.Instant;
import java.util.UUID;

/**
 * Dữ liệu rút gọn của bài Wiki công khai.
 *
 * Dùng cho card/dòng danh sách, không chứa content đầy đủ.
 */
public record PublishedWikiArticleListItemDTO(
        UUID id,
        String title,
        String slug,
        String articleType,
        String summary,
        Instant publishedAt,
        Instant updatedAt,
        UUID coverMediaAssetId,
        int coverPositionX,
        int coverPositionY
) {

    public PublishedWikiArticleListItemDTO(
            UUID id,
            String title,
            String slug,
            String articleType,
            String summary,
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
                publishedAt,
                updatedAt,
                coverMediaAssetId,
                50,
                50
        );
    }

    public PublishedWikiArticleListItemDTO(
            UUID id,
            String title,
            String slug,
            String articleType,
            String summary,
            Instant publishedAt,
            Instant updatedAt
    ) {
        this(
                id,
                title,
                slug,
                articleType,
                summary,
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