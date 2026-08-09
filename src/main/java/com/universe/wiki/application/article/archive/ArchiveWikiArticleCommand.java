package com.universe.wiki.application.article.archive;

import java.util.UUID;

/**
 * Yêu cầu lưu trữ một bài viết Wiki.
 */
public record ArchiveWikiArticleCommand(
        UUID articleId,
        String editSummary,
        UUID actorId
) {
}