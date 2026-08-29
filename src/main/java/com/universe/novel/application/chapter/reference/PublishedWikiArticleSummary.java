package com.universe.novel.application.chapter.reference;

import java.util.Objects;
import java.util.UUID;

/**
 * Model gọn nhẹ đại diện cho bài viết Wiki đã xuất bản được Novel module quản lý.
 */
public record PublishedWikiArticleSummary(
        UUID id,
        String title,
        String slug,
        String articleType,
        String summary
) {
    public PublishedWikiArticleSummary {
        Objects.requireNonNull(id, "ID bài viết không được để trống.");
        Objects.requireNonNull(title, "Tiêu đề không được để trống.");
        Objects.requireNonNull(slug, "Slug không được để trống.");
        Objects.requireNonNull(articleType, "Loại bài viết không được để trống.");
    }

    public PublishedWikiArticleSummary(
            UUID id,
            String title,
            String slug,
            String articleType
    ) {
        this(id, title, slug, articleType, null);
    }
}
