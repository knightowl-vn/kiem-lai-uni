package com.universe.wiki.application.article.query.list;

import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;

/**
 * Điều kiện truy vấn danh sách bài Wiki cho trang quản trị.
 *
 * page bắt đầu từ 0.
 */
public record ListWikiArticlesQuery(
        String keyword,
        ArticleType articleType,
        ArticleStatus status,
        int page,
        int size
) {
}