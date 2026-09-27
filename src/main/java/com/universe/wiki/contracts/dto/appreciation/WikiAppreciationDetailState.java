package com.universe.wiki.contracts.dto.appreciation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Trạng thái đánh giá mức độ yêu thích hiển thị trên trang chi tiết Wiki công khai.
 *
 * @param average điểm đánh giá trung bình của cộng đồng (null khi chưa có đánh giá nào)
 * @param count tổng số lượt đánh giá của cộng đồng (>= 0)
 * @param viewerValue điểm đánh giá hiện hành của người xem theo thang sao nhìn thấy (1.0..5.0, hoặc null nếu chưa đánh giá / ẩn danh)
 */
public record WikiAppreciationDetailState(
        BigDecimal average,
        long count,
        BigDecimal viewerValue
) {
    public WikiAppreciationDetailState {
        if (count < 0) {
            throw new IllegalArgumentException("Số lượng đánh giá không được âm: " + count);
        }
        if (count == 0 && average != null) {
            throw new IllegalArgumentException("Khi chưa có đánh giá nào, điểm trung bình phải là null.");
        }
        if (viewerValue != null) {
            if (viewerValue.compareTo(new BigDecimal("1.0")) < 0 || viewerValue.compareTo(new BigDecimal("5.0")) > 0) {
                throw new IllegalArgumentException("Điểm đánh giá của người xem phải từ 1.0 đến 5.0: " + viewerValue);
            }
        }
    }

    public static WikiAppreciationDetailState empty() {
        return new WikiAppreciationDetailState(null, 0L, null);
    }

    public BigDecimal displayAverage() {
        return average == null ? null : average.setScale(1, RoundingMode.HALF_UP);
    }
}
