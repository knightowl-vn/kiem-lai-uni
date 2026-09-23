package com.universe.wiki.domain.appreciation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WikiAppreciationSummary Domain Model Tests")
class WikiAppreciationSummaryTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("Khởi tạo empty summary hợp lệ: count=0, average=null, displayAverage=null")
    void shouldCreateEmptySummary() {
        WikiAppreciationSummary empty = WikiAppreciationSummary.empty(ARTICLE_ID);

        assertThat(empty).isNotNull();
        assertThat(empty.wikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(empty.count()).isEqualTo(0L);
        assertThat(empty.average()).isNull();
        assertThat(empty.displayAverage()).isNull();
    }

    @Test
    @DisplayName("Khởi tạo summary có dữ liệu: count > 0, average != null")
    void shouldCreatePopulatedSummary() {
        BigDecimal average = new BigDecimal("4.875");
        WikiAppreciationSummary summary = new WikiAppreciationSummary(ARTICLE_ID, average, 42L);

        assertThat(summary).isNotNull();
        assertThat(summary.wikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(summary.count()).isEqualTo(42L);
        assertThat(summary.average()).isEqualByComparingTo(average);
    }

    @Test
    @DisplayName("Kiểm chứng displayAverage làm tròn 1 chữ số thập phân HALF_UP chính xác")
    void shouldFormatDisplayAverageWithHalfUp() {
        // Case 1: 4.75 -> 4.8
        WikiAppreciationSummary summary1 = new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.75"), 2L);
        assertThat(summary1.displayAverage()).isEqualByComparingTo(new BigDecimal("4.8"));
        assertThat(summary1.displayAverage().scale()).isEqualTo(1);
        assertThat(summary1.average()).isEqualByComparingTo(new BigDecimal("4.75")); // Không phá hủy giá trị gốc

        // Case 2: 4.65 -> 4.7 (Kiểm chứng phân biệt rõ HALF_UP vs HALF_EVEN)
        WikiAppreciationSummary summary2 = new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.65"), 10L);
        assertThat(summary2.displayAverage()).isEqualByComparingTo(new BigDecimal("4.7"));
        assertThat(summary2.displayAverage().scale()).isEqualTo(1);
        assertThat(summary2.average()).isEqualByComparingTo(new BigDecimal("4.65")); // Không phá hủy giá trị gốc

        // Case 3: 4.64 -> 4.6
        WikiAppreciationSummary summary3 = new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.64"), 10L);
        assertThat(summary3.displayAverage()).isEqualByComparingTo(new BigDecimal("4.6"));
        assertThat(summary3.average()).isEqualByComparingTo(new BigDecimal("4.64"));
    }

    @Test
    @DisplayName("Từ chối khi wikiArticleId bị null")
    void shouldRejectNullWikiArticleId() {
        assertThatThrownBy(() -> new WikiAppreciationSummary(null, new BigDecimal("4.0"), 1L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết Wiki không được để trống.");

        assertThatThrownBy(() -> WikiAppreciationSummary.empty(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết Wiki không được để trống.");
    }

    @Test
    @DisplayName("Từ chối count âm")
    void shouldRejectNegativeCount() {
        assertThatThrownBy(() -> new WikiAppreciationSummary(ARTICLE_ID, null, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Số lượng đánh giá không được âm");
    }

    @Test
    @DisplayName("Từ chối count=0 nhưng average != null (vi phạm bất biến rỗng)")
    void shouldRejectZeroCountWithNonNullAverage() {
        assertThatThrownBy(() -> new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("0.0"), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Khi chưa có lượt đánh giá (count=0), điểm trung bình phải là null.");
    }

    @Test
    @DisplayName("Từ chối count > 0 nhưng average == null (vi phạm bất biến có dữ liệu)")
    void shouldRejectPositiveCountWithNullAverage() {
        assertThatThrownBy(() -> new WikiAppreciationSummary(ARTICLE_ID, null, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Khi đã có lượt đánh giá (count>0), điểm trung bình không được để trống.");
    }
}
