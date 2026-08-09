package com.universe.wiki.application.article.query.published;

import com.universe.wiki.domain.article.ArticleType;

/**
 * Điều kiện tìm kiếm danh sách bài Wiki công khai.
 *
 * keyword và articleType được phép null.
 * page bắt đầu từ 0.
 */
public record ListPublishedWikiArticlesQuery(
        String keyword,
        ArticleType articleType,
        int page,
        int size
) {
}