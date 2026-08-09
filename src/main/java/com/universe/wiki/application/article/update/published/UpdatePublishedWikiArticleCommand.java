package com.universe.wiki.application.article.update.published;

import java.util.UUID;

/**
 * Yêu cầu cập nhật nội dung của một bài Wiki đã xuất bản.
 *
 * Không cho phép đổi title, slug hoặc article type.
 */
public record UpdatePublishedWikiArticleCommand(
        UUID articleId,
        String summary,
        String content,
        String editSummary,
        UUID actorId
) {
}