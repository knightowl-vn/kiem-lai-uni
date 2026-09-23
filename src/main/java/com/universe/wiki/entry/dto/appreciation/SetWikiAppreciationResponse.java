package com.universe.wiki.entry.dto.appreciation;

import com.universe.wiki.application.appreciation.SetWikiAppreciationResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload phản hồi sau khi thiết lập hoặc cập nhật mức độ yêu thích của bài viết Wiki.
 *
 * <p>Bảo toàn độ chính xác của BigDecimal điểm trung bình (average) từ database aggregate,
 * đồng thời cung cấp điểm hiển thị được làm tròn HALF_UP 1 chữ số thập phân (displayAverage)
 * để client JavaScript hiển thị đồng nhất tuyệt đối với giao diện server-rendered.
 *
 * @param wikiArticleId định danh bài viết Wiki
 * @param value điểm đánh giá của người dùng theo thang sao nhìn thấy (1.0..5.0)
 * @param average điểm trung bình nguyên bản của cộng đồng (null khi count=0)
 * @param displayAverage điểm trung bình hiển thị 1 chữ số thập phân làm tròn HALF_UP (null khi count=0)
 * @param count tổng số lượt đánh giá của cộng đồng (>= 0)
 * @param changed true nếu là đánh giá mới hoặc đổi điểm; false nếu cùng giá trị (same-value no-op)
 */
public record SetWikiAppreciationResponse(
        UUID wikiArticleId,
        BigDecimal value,
        BigDecimal average,
        String displayAverage,
        long count,
        boolean changed
) {
    public SetWikiAppreciationResponse {
        Objects.requireNonNull(wikiArticleId, "wikiArticleId không được để trống.");
        Objects.requireNonNull(value, "value không được để trống.");
        if (count < 0) {
            throw new IllegalArgumentException("count không được âm: " + count);
        }
    }

    public static SetWikiAppreciationResponse from(SetWikiAppreciationResult result) {
        Objects.requireNonNull(result, "SetWikiAppreciationResult không được để trống.");
        String displayAvg = result.average() == null
                ? null
                : result.average().setScale(1, RoundingMode.HALF_UP).toPlainString();
        return new SetWikiAppreciationResponse(
                result.wikiArticleId(),
                result.score().toStars(),
                result.average(),
                displayAvg,
                result.count(),
                result.changed()
        );
    }
}
