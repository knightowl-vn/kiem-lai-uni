package com.universe.wiki.entry.dto.appreciation;

import com.universe.wiki.application.appreciation.SetWikiAppreciationResult;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload phản hồi sau khi thiết lập hoặc cập nhật mức độ yêu thích của bài viết Wiki.
 *
 * <p>Bảo toàn độ chính xác của BigDecimal điểm trung bình từ database aggregate,
 * không áp dụng làm tròn hay làm lộ định danh nội bộ/dấu thời gian bản ghi.
 *
 * @param wikiArticleId định danh bài viết Wiki
 * @param value điểm đánh giá của người dùng (1..5)
 * @param average điểm trung bình của cộng đồng (null khi count=0)
 * @param count tổng số lượt đánh giá của cộng đồng (>= 0)
 * @param changed true nếu là đánh giá mới hoặc đổi điểm; false nếu cùng giá trị (same-value no-op)
 */
public record SetWikiAppreciationResponse(
        UUID wikiArticleId,
        int value,
        BigDecimal average,
        long count,
        boolean changed
) {
    public SetWikiAppreciationResponse {
        Objects.requireNonNull(wikiArticleId, "wikiArticleId không được để trống.");
        if (count < 0) {
            throw new IllegalArgumentException("count không được âm: " + count);
        }
    }

    public static SetWikiAppreciationResponse from(SetWikiAppreciationResult result) {
        Objects.requireNonNull(result, "SetWikiAppreciationResult không được để trống.");
        return new SetWikiAppreciationResponse(
                result.wikiArticleId(),
                result.value(),
                result.average(),
                result.count(),
                result.changed()
        );
    }
}
