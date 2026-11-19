package com.universe.wiki.application.article.query.published;

import com.universe.wiki.domain.article.ArticleType;

/**
 * Query for the public Wiki catalog overview index.
 *
 * @param keyword optional search term (case-insensitive substring match)
 * @param articleType optional article type filter
 * @param page zero-based page number (must be >= 0)
 * @param size page size (must be >= 1 and <= 100)
 */
public record GetPublicWikiCatalogOverviewQuery(
        String keyword,
        ArticleType articleType,
        int page,
        int size
) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    public GetPublicWikiCatalogOverviewQuery {
        if (page < 0) {
            throw new IllegalArgumentException("Page không được nhỏ hơn 0.");
        }
        if (size < 1) {
            throw new IllegalArgumentException("Page size phải lớn hơn hoặc bằng 1.");
        }
        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size không được vượt quá " + MAX_PAGE_SIZE + ".");
        }
    }

    public static GetPublicWikiCatalogOverviewQuery of(String keyword, ArticleType articleType, int page, int size) {
        return new GetPublicWikiCatalogOverviewQuery(keyword, articleType, page, size);
    }
}
