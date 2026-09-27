package com.universe.wiki.application.article.create;

import com.universe.wiki.domain.article.ArticleType;

import java.util.UUID;

/**
 * Yêu cầu tạo một bài Wiki hoàn chỉnh
 * và xuất bản ngay trong lần tạo đầu tiên.
 */
public record CreateAndPublishWikiArticleCommand(
        String title,
        ArticleType articleType,
        String summary,
        String content,
        String editSummary,
        UUID actorId,
        UUID coverMediaAssetId,
        Integer coverPositionX,
        Integer coverPositionY
) {
    public CreateAndPublishWikiArticleCommand(
            String title,
            ArticleType articleType,
            String summary,
            String content,
            String editSummary,
            UUID actorId,
            UUID coverMediaAssetId
    ) {
        this(title, articleType, summary, content, editSummary, actorId, coverMediaAssetId, 50, 50);
    }

    public CreateAndPublishWikiArticleCommand(
            String title,
            ArticleType articleType,
            String summary,
            String content,
            String editSummary,
            UUID actorId
    ) {
        this(title, articleType, summary, content, editSummary, actorId, null, 50, 50);
    }
}