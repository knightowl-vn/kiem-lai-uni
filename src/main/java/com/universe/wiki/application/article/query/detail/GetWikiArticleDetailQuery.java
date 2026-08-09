package com.universe.wiki.application.article.query.detail;

import java.util.UUID;

/**
 * Yêu cầu lấy chi tiết một bài viết Wiki theo ID.
 */
public record GetWikiArticleDetailQuery(
        UUID articleId
) {
}