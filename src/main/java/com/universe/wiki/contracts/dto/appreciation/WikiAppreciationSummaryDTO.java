package com.universe.wiki.contracts.dto.appreciation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable DTO representing community appreciation summary for a Wiki article in public contracts.
 */
public record WikiAppreciationSummaryDTO(
        UUID wikiArticleId,
        BigDecimal average,
        long count
) {
    public WikiAppreciationSummaryDTO {
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

    public static WikiAppreciationSummaryDTO empty(UUID wikiArticleId) {
        return new WikiAppreciationSummaryDTO(wikiArticleId, null, 0L);
    }

    public BigDecimal displayAverage() {
        if (average == null) {
            return null;
        }
        return average.setScale(1, RoundingMode.HALF_UP);
    }
}
