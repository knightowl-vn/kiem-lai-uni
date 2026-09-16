package com.universe.wiki.contracts.dto.saved;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO đại diện cho một bài viết Wiki đã lưu trong danh sách bài lưu của người dùng.
 *
 * Hỗ trợ phân biệt:
 * - AVAILABLE: bài viết đang PUBLISHED (có đầy đủ metadata tiêu đề, slug, tóm tắt, loại bài);
 * - UNAVAILABLE: bài viết đã bị gỡ xuất bản (DRAFT / ARCHIVED) nhưng vẫn còn trong danh sách lưu
 *   (không để lộ metadata nội dung bài viết).
 */
public record SavedWikiArticleItemDTO(
        UUID savedId,
        UUID articleId,
        Instant savedAt,
        boolean available,
        String title,
        String slug,
        String articleType,
        String summary
) {

    public static SavedWikiArticleItemDTO available(
            UUID savedId,
            UUID articleId,
            Instant savedAt,
            String title,
            String slug,
            String articleType,
            String summary
    ) {
        return new SavedWikiArticleItemDTO(
                savedId,
                articleId,
                savedAt,
                true,
                title,
                slug,
                articleType,
                summary
        );
    }

    public static SavedWikiArticleItemDTO unavailable(
            UUID savedId,
            UUID articleId,
            Instant savedAt
    ) {
        return new SavedWikiArticleItemDTO(
                savedId,
                articleId,
                savedAt,
                false,
                null,
                null,
                null,
                null
        );
    }
}
