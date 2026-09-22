package com.universe.wiki.contracts.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot dữ liệu tối giản phục vụ việc kiểm tra tính đủ điều kiện (eligibility) của bài viết Wiki
 * (ví dụ: đánh giá mức độ yêu thích) mà không hydrate toàn bộ content, summary, audit actor IDs, hay versions.
 */
public record WikiArticleEligibilitySnapshot(
        UUID id,
        String articleType,
        String status
) {
    public WikiArticleEligibilitySnapshot {
        Objects.requireNonNull(id, "ID bài viết Wiki không được để trống.");
        Objects.requireNonNull(articleType, "Loại bài viết Wiki không được để trống.");
        Objects.requireNonNull(status, "Trạng thái bài viết Wiki không được để trống.");
    }
}
