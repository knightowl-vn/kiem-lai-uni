package com.universe.wiki.application.revision.query.detail;

import java.util.UUID;

/**
 * Yêu cầu lấy chi tiết một revision của bài Wiki.
 */
public record GetWikiArticleRevisionDetailQuery(
        UUID articleId,
        long revisionNumber
) {
}