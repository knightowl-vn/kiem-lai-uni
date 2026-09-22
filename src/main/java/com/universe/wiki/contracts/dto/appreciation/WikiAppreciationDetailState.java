package com.universe.wiki.contracts.dto.appreciation;

import java.math.BigDecimal;

/**
 * Trạng thái đánh giá mức độ yêu thích hiển thị trên trang chi tiết Wiki công khai.
 *
 * @param average điểm đánh giá trung bình của cộng đồng (null khi chưa có đánh giá nào)
 * @param count tổng số lượt đánh giá của cộng đồng (>= 0)
 * @param viewerValue điểm đánh giá hiện hành của người xem (1..5, hoặc null nếu chưa đánh giá / ẩn danh)
 */
public record WikiAppreciationDetailState(
        BigDecimal average,
        long count,
        Integer viewerValue
) {
    public WikiAppreciationDetailState {
        if (count < 0) {
            throw new IllegalArgumentException("Số lượng đánh giá không được âm: " + count);
        }
        if (count == 0 && average != null) {
            throw new IllegalArgumentException("Khi chưa có đánh giá nào, điểm trung bình phải là null.");
        }
        if (viewerValue != null && (viewerValue < 1 || viewerValue > 5)) {
            throw new IllegalArgumentException("Điểm đánh giá của người xem phải từ 1 đến 5: " + viewerValue);
        }
    }

    public static WikiAppreciationDetailState empty() {
        return new WikiAppreciationDetailState(null, 0L, null);
    }
}
