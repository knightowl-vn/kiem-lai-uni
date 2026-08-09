package com.universe.wiki.application.article.query.published;

import com.universe.wiki.domain.article.ArticleType;

public record GetPublishedWikiArticleQuery(
        ArticleType articleType,
        String slug
) {
}