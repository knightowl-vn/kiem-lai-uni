package com.universe.wiki.application.article.query.published;

import com.universe.wiki.domain.article.ArticleType;

import java.util.UUID;

/**
 * Query for the public Wiki article detail read coordinator.
 *
 * @param articleType resolved article type
 * @param slug article slug path variable
 * @param viewerUserId optional authenticated viewer user ID (null for anonymous guests)
 */
public record GetPublicWikiArticleDetailQuery(
        ArticleType articleType,
        String slug,
        UUID viewerUserId
) {

    public static GetPublicWikiArticleDetailQuery of(
            ArticleType articleType,
            String slug,
            UUID viewerUserId
    ) {
        return new GetPublicWikiArticleDetailQuery(articleType, slug, viewerUserId);
    }
}
