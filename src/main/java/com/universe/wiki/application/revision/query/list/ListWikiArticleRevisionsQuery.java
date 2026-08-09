package com.universe.wiki.application.revision.query.list;

import java.util.UUID;

/**
 * Yêu cầu lấy lịch sử revision của một bài Wiki.
 *
 * page bắt đầu từ 0.
 */
public record ListWikiArticleRevisionsQuery(
        UUID articleId,
        int page,
        int size
) {
}