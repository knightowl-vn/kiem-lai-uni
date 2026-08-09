package com.universe.wiki.application.article.restore;

import java.util.UUID;

/**
 * Yêu cầu khôi phục bài viết từ một revision cũ.
 *
 * Bài sau khi khôi phục luôn trở về trạng thái DRAFT.
 */
public record RestoreWikiArticleCommand(
        UUID articleId,
        long sourceRevisionNumber,
        String editSummary,
        UUID actorId
) {
}