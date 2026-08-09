package com.universe.wiki.application.article.create;

import com.universe.wiki.domain.article.ArticleType;

import java.util.UUID;

public record CreateWikiArticleCommand(
        String title,
        ArticleType articleType,
        String summary,
        String content,
        String editSummary,
        UUID actorId
) {
}