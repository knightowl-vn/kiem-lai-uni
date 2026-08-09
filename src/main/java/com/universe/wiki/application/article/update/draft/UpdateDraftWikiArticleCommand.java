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
        UUID actorId
) {
}