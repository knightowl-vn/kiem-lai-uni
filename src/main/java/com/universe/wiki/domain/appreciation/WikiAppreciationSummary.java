package com.universe.wiki.domain.appreciation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * Model bất biến đại diện cho tổng hợp đánh giá mức độ yêu thích (Appreciation Summary)
 * của cộng đồng đối với một bài viết Wiki.
 *
 * Bất biến:
 * - wikiArticleId không được null;
 * - count >= 0;
 * - count == 0 => average == null;
 * - count > 0 => average != null;
 * - Không bao gồm trạng thái per-viewer (viewerRating);
 * - average biểu diễn dạng BigDecimal nguyên gốc từ database, không làm tròn hay ép kiểu double.
 */
public record WikiAppreciationSummary(
        UUID wikiArticleId,
        BigDecimal average,
        long count
) {
    public WikiAppreciationSummary {
        Objects.requireNonNull(wikiArticleId, "ID bài viết Wiki không được để trống.");
        if (count < 0) {
            throw new IllegalArgumentException("Số lượng đánh giá không được âm: " + count);
        }
        if (count == 0 && average != null) {
            throw new IllegalArgumentException("Khi chưa có lượt đánh giá (count=0), điểm trung bình phải là null.");
        }
        if (count > 0 && average == null) {
            throw new IllegalArgumentException("Khi đã có lượt đánh giá (count>0), điểm trung bình không được để trống.");
        }
    }

    /**
     * Tạo summary rỗng cho bài viết chưa có lượt đánh giá nào.
     */
    public static WikiAppreciationSummary empty(UUID wikiArticleId) {
        return new WikiAppreciationSummary(wikiArticleId, null, 0L);
    }

    /**
     * Chuyển đổi điểm trung bình cộng đồng sang dạng hiển thị 1 chữ số thập phân,
     * áp dụng quy tắc làm tròn HALF_UP chuẩn mực của hệ thống.
     *
     * <p>Đảm bảo:
     * <ul>
     *   <li>4.75 -> 4.8</li>
     *   <li>4.65 -> 4.7</li>
     *   <li>4.64 -> 4.6</li>
     *   <li>Không làm tròn hay biến đổi điểm trung bình nguyên bản (average).</li>
     * </ul>
     */
    public BigDecimal displayAverage() {
        if (average == null) {
            return null;
        }
        return average.setScale(1, RoundingMode.HALF_UP);
    }
}
