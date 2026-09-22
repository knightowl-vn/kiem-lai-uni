package com.universe.wiki.application.appreciation;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Kết quả trả về sau thao tác thiết lập/cập nhật đánh giá mức độ yêu thích.
 * Phục vụ composition cho tầng controller/UI trong các tranche tiếp theo.
 *
 * @param wikiArticleId định danh bài viết Wiki
 * @param value điểm đánh giá của người dùng (1..5)
 * @param average điểm trung bình cộng đồng sau khi cập nhật
 * @param count tổng số lượt đánh giá cộng đồng sau khi cập nhật
 * @param changed true nếu là đánh giá lần đầu hoặc có thay đổi điểm số; false nếu là thao tác no-op cùng điểm số
 */
public record SetWikiAppreciationResult(
        UUID wikiArticleId,
        int value,
        BigDecimal average,
        long count,
        boolean changed
) {
    public SetWikiAppreciationResult {
        Objects.requireNonNull(wikiArticleId, "ID bài viết Wiki không được để trống.");
    }
}
