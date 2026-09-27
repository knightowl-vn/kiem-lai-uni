package com.universe.wiki.application.article.create;

import com.universe.wiki.domain.article.ArticleType;

import java.util.UUID;

public record CreateWikiArticleCommand(
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
    public CreateWikiArticleCommand(
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

    public CreateWikiArticleCommand(
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