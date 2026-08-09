package com.universe.wiki.application.article.publish;

import java.util.UUID;

/**
 * Yêu cầu xuất bản một bài viết Wiki.
 */
public record PublishWikiArticleCommand(
        UUID articleId,
        String editSummary,
        UUID actorId
) {
}