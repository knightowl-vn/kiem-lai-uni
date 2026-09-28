package com.universe.wiki.contracts.dto.search;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.wiki.domain.article.ArticleType;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO mô tả một mục kết quả tìm kiếm điều hướng Wiki.
 *
 * Chứa thông tin siêu dữ liệu điều hướng và trình bày rút gọn, không chứa content/body đầy đủ.
 */
public record WikiNavigationalSearchItemDTO(
        UUID id,
        ArticleType articleType,
        String title,
        String slug,
        String canonicalUrl,
        String matchedAlias,
        String summary,
        Instant updatedAt,
        UUID coverMediaAssetId,
        int coverPositionX,
        int coverPositionY
) {
    public WikiNavigationalSearchItemDTO(
            UUID id,
            ArticleType articleType,
            String title,
            String slug,
            String canonicalUrl,
            String matchedAlias
    ) {
        this(
                id,
                articleType,
                title,
                slug,
                canonicalUrl,
                matchedAlias,
                null,
                null,
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

    public boolean isAppreciationEligible() {
        return articleType == ArticleType.CHARACTER || articleType == ArticleType.FACTION;
    }
}
