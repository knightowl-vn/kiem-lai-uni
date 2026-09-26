package com.universe.wiki.entry.web;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.wiki.contracts.dto.saved.SavedWikiArticleItemDTO;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.entry.web.support.ArticleTypePathMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * View model cho từng bài viết Wiki đã lưu trong danh sách hiển thị.
 *
 * Mở rộng metadata hiển thị với {@code articleTypePath} chuẩn hóa thông qua {@link ArticleTypePathMapper},
 * cùng với thông tin ảnh bìa và vị trí trọng tâm đồng bộ với thẻ bài viết Wiki chuẩn.
 */
public record SavedWikiArticleViewItem(
        UUID savedId,
        UUID articleId,
        Instant savedAt,
        boolean available,
        String title,
        String slug,
        String articleType,
        String articleTypePath,
        String summary,
        UUID coverMediaAssetId,
        int coverPositionX,
        int coverPositionY
) {

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

    public static SavedWikiArticleViewItem from(
            SavedWikiArticleItemDTO dto,
            ArticleTypePathMapper articleTypePathMapper
    ) {
        if (dto == null) {
            return null;
        }

        if (!dto.available()) {
            return new SavedWikiArticleViewItem(
                    dto.savedId(),
                    dto.articleId(),
                    dto.savedAt(),
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    50,
                    50
            );
        }

        String articleTypePath = null;
        if (dto.articleType() != null && articleTypePathMapper != null) {
            try {
                ArticleType type = ArticleType.valueOf(dto.articleType());
                articleTypePath = articleTypePathMapper.toPath(type);
            } catch (IllegalArgumentException | NullPointerException ignored) {
                articleTypePath = null;
            }
        }

        return new SavedWikiArticleViewItem(
                dto.savedId(),
                dto.articleId(),
                dto.savedAt(),
                true,
                dto.title(),
                dto.slug(),
                dto.articleType(),
                articleTypePath,
                dto.summary(),
                dto.coverMediaAssetId(),
                dto.coverPositionX(),
                dto.coverPositionY()
        );
    }
}
