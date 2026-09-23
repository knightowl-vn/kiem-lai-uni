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
        UUID actorId,
        UUID coverMediaAssetId,
        Integer coverPositionX,
        Integer coverPositionY,
        boolean updateCover
) {
    public UpdatePublishedWikiArticleCommand(
            UUID articleId,
            String summary,
            String content,
            String editSummary,
            UUID actorId,
            Integer coverPositionX,
            Integer coverPositionY
    ) {
        this(articleId, summary, content, editSummary, actorId, null,
                coverPositionX != null ? coverPositionX : 50,
                coverPositionY != null ? coverPositionY : 50, false);
    }

    public UpdatePublishedWikiArticleCommand(
            UUID articleId,
            String summary,
            String content,
            String editSummary,
            UUID actorId,
            UUID coverMediaAssetId,
            Integer coverPositionX,
            Integer coverPositionY
    ) {
        this(articleId, summary, content, editSummary, actorId, coverMediaAssetId,
                coverPositionX != null ? coverPositionX : 50,
                coverPositionY != null ? coverPositionY : 50, true);
    }

    public UpdatePublishedWikiArticleCommand(
            UUID articleId,
            String summary,
            String content,
            String editSummary,
            UUID actorId,
            UUID coverMediaAssetId,
            boolean updateCover
    ) {
        this(articleId, summary, content, editSummary, actorId, coverMediaAssetId, 50, 50, updateCover);
    }

    public UpdatePublishedWikiArticleCommand(
            UUID articleId,
            String summary,
            String content,
            String editSummary,
            UUID actorId,
            UUID coverMediaAssetId
    ) {
        this(articleId, summary, content, editSummary, actorId, coverMediaAssetId, 50, 50, true);
    }

    public UpdatePublishedWikiArticleCommand(
            UUID articleId,
            String summary,
            String content,
            String editSummary,
            UUID actorId
    ) {
        this(articleId, summary, content, editSummary, actorId, null, 50, 50, false);
    }
}