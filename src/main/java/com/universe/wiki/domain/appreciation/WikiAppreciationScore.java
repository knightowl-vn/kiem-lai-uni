package com.universe.wiki.domain.appreciation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value Object bất biến đại diện cho điểm đánh giá mức độ yêu thích (Appreciation Score) trong module Wiki.
 *
 * <p>Quy tắc biểu diễn:
 * <ul>
 *   <li>Thang đo hiển thị: 1.0 đến 5.0 sao với bước nhảy 0.5 (1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0);</li>
 *   <li>Thang đo lưu trữ nội bộ: Đơn vị nửa sao (half-star units) từ 2 đến 10 (1.0 -> 2, 1.5 -> 3, ..., 5.0 -> 10);</li>
 *   <li>Độc quyền thuộc ngữ cảnh Wiki, không rò rỉ đơn vị 2..10 ra ngoài tầng HTTP API/Template;</li>
 *   <li>Record equality là cơ chế so sánh cùng giá trị (same-value) chuẩn tắc.</li>
 * </ul>
 *
 * @param halfStarUnits số đơn vị nửa sao (2..10)
 */
public record WikiAppreciationScore(int halfStarUnits) {

    public static final int MIN_HALF_STAR_UNITS = 2;
    public static final int MAX_HALF_STAR_UNITS = 10;

    public static final BigDecimal MIN_STARS = new BigDecimal("1.0");
    public static final BigDecimal MAX_STARS = new BigDecimal("5.0");

    public WikiAppreciationScore {
        if (halfStarUnits < MIN_HALF_STAR_UNITS || halfStarUnits > MAX_HALF_STAR_UNITS) {
            throw new IllegalArgumentException(
                    "Số đơn vị nửa sao phải từ " + MIN_HALF_STAR_UNITS + " đến " + MAX_HALF_STAR_UNITS + ", nhận được: " + halfStarUnits
            );
        }
    }

    /**
     * Tạo WikiAppreciationScore từ số đơn vị nửa sao nguyên bản (2..10).
     */
    public static WikiAppreciationScore fromHalfStarUnits(int units) {
        return new WikiAppreciationScore(units);
    }

    /**
     * Tạo WikiAppreciationScore từ số sao nhìn thấy (1.0..5.0, bước 0.5).
     *
     * @param stars số sao cần chuyển đổi (BigDecimal)
     * @return đối tượng WikiAppreciationScore hợp lệ
     * @throws IllegalArgumentException nếu stars null, ngoài khoảng [1.0, 5.0], hoặc không chia hết cho 0.5
     */
    public static WikiAppreciationScore fromStars(BigDecimal stars) {
        if (stars == null) {
            throw new IllegalArgumentException("Điểm đánh giá sao không được để trống.");
        }

        if (stars.compareTo(MIN_STARS) < 0 || stars.compareTo(MAX_STARS) > 0) {
            throw new IllegalArgumentException(
                    "Điểm đánh giá sao phải nằm trong khoảng từ " + MIN_STARS + " đến " + MAX_STARS + ", nhận được: " + stars
            );
        }

        BigDecimal doubled = stars.multiply(BigDecimal.valueOf(2));
        if (doubled.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "Điểm đánh giá phải theo bước 0.5 sao, nhận được: " + stars
            );
        }

        int units = doubled.intValueExact();
        return fromHalfStarUnits(units);
    }

    /**
     * Chuyển đổi số đơn vị nửa sao thành số sao nhìn thấy tương ứng (1.0..5.0 với 1 chữ số thập phân).
     */
    public BigDecimal toStars() {
        return BigDecimal.valueOf(halfStarUnits)
                .divide(BigDecimal.valueOf(2), 1, RoundingMode.UNNECESSARY);
    }
}
