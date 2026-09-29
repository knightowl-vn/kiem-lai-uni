package com.universe.wiki.application.article.query.search;

import com.universe.wiki.domain.article.ArticleType;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal DTO chứa thông tin ứng viên khớp alias phục vụ use case tìm kiếm điều hướng.
 */
public record WikiArticleAliasSearchMatchDTO(
        UUID articleId,
        String title,
        String slug,
        ArticleType articleType,
        String alias,
        String summary,
        Instant updatedAt,
        UUID coverMediaAssetId,
        int coverPositionX,
        int coverPositionY
) {
    public WikiArticleAliasSearchMatchDTO(
            UUID articleId,
            String title,
            String slug,
            ArticleType articleType,
            String alias
    ) {
        this(articleId, title, slug, articleType, alias, null, null, null, 50, 50);
    }
}
