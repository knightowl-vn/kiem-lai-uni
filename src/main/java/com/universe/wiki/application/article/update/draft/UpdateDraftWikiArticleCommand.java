package com.universe.wiki.application.article.update.draft;

import com.universe.wiki.domain.article.ArticleType;

import java.util.UUID;

public record UpdateDraftWikiArticleCommand(
        UUID articleId,
        String title,
        ArticleType articleType,
        String summary,
        String content,
        String editSummary,
        UUID actorId,
        UUID coverMediaAssetId,
        Integer coverPositionX,
        Integer coverPositionY,
        boolean updateCover
) {
    public UpdateDraftWikiArticleCommand(
            UUID articleId,
            String title,
            ArticleType articleType,
            String summary,
            String content,
            String editSummary,
            UUID actorId,
            Integer coverPositionX,
            Integer coverPositionY
    ) {
        this(articleId, title, articleType, summary, content, editSummary, actorId, null,
                coverPositionX != null ? coverPositionX : 50,
                coverPositionY != null ? coverPositionY : 50, false);
    }

    public UpdateDraftWikiArticleCommand(
            UUID articleId,
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
        this(articleId, title, articleType, summary, content, editSummary, actorId, coverMediaAssetId,
                coverPositionX != null ? coverPositionX : 50,
                coverPositionY != null ? coverPositionY : 50, true);
    }

    public UpdateDraftWikiArticleCommand(
            UUID articleId,
            String title,
            ArticleType articleType,
            String summary,
            String content,
            String editSummary,
            UUID actorId,
            UUID coverMediaAssetId,
            boolean updateCover
    ) {
        this(articleId, title, articleType, summary, content, editSummary, actorId, coverMediaAssetId,
                50, 50, updateCover);
    }

    public UpdateDraftWikiArticleCommand(
            UUID articleId,
            String title,
            ArticleType articleType,
            String summary,
            String content,
            String editSummary,
            UUID actorId,
            UUID coverMediaAssetId
    ) {
        this(articleId, title, articleType, summary, content, editSummary, actorId, coverMediaAssetId,
                50, 50, true);
    }

    public UpdateDraftWikiArticleCommand(
            UUID articleId,
            String title,
            ArticleType articleType,
            String summary,
            String content,
            String editSummary,
            UUID actorId
    ) {
        this(articleId, title, articleType, summary, content, editSummary, actorId, null, 50, 50, false);
    }
}