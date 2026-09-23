package com.universe.wiki.domain.appreciation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WikiAppreciationScore Domain Value Object Tests")
class WikiAppreciationScoreTest {

    @ParameterizedTest(name = "{0} stars -> {1} half-star units")
    @CsvSource({
            "1.0, 2",
            "1.5, 3",
            "2.0, 4",
            "2.5, 5",
            "3.0, 6",
            "3.5, 7",
            "4.0, 8",
            "4.5, 9",
            "5.0, 10"
    })
    @DisplayName("Khởi tạo hợp lệ từ 9 giá trị sao và chuyển đổi hai chiều chính xác")
    void shouldCreateAndConvertBetweenStarsAndUnits(String starsStr, int expectedUnits) {
        BigDecimal stars = new BigDecimal(starsStr);

        WikiAppreciationScore scoreFromStars = WikiAppreciationScore.fromStars(stars);
        assertThat(scoreFromStars.halfStarUnits()).isEqualTo(expectedUnits);
        assertThat(scoreFromStars.toStars()).isEqualByComparingTo(stars);
        assertThat(scoreFromStars.toStars().scale()).isEqualTo(1);

        WikiAppreciationScore scoreFromUnits = WikiAppreciationScore.fromHalfStarUnits(expectedUnits);
        assertThat(scoreFromUnits).isEqualTo(scoreFromStars);
        assertThat(scoreFromUnits.toStars()).isEqualByComparingTo(stars);
    }

    @ParameterizedTest
    @ValueSource(ints = { -1, 0, 1, 11, 12, 100 })
    @DisplayName("Từ chối số đơn vị nửa sao ngoài khoảng 2..10")
    void shouldRejectInvalidHalfStarUnits(int invalidUnits) {
        assertThatThrownBy(() -> WikiAppreciationScore.fromHalfStarUnits(invalidUnits))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Số đơn vị nửa sao phải từ 2 đến 10");
    }

    @ParameterizedTest
    @ValueSource(strings = { "0.5", "0.9", "1.1", "1.2", "4.7", "5.1", "5.5", "4.5000000000000001", "1.2500000000000000" })
    @DisplayName("Từ chối các giá trị sao không thuộc bước 0.5 hoặc ngoài khoảng 1.0..5.0")
    void shouldRejectInvalidStars(String invalidStarsStr) {
        BigDecimal invalidStars = new BigDecimal(invalidStarsStr);
        assertThatThrownBy(() -> WikiAppreciationScore.fromStars(invalidStars))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Từ chối null khi gọi fromStars")
    void shouldRejectNullStars() {
        assertThatThrownBy(() -> WikiAppreciationScore.fromStars(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Điểm đánh giá sao không được để trống.");
    }

    @Test
    @DisplayName("Bình đẳng bản ghi (record equality) hoạt động chuẩn xác")
    void shouldSupportRecordEquality() {
        WikiAppreciationScore a = WikiAppreciationScore.fromStars(new BigDecimal("4.5"));
        WikiAppreciationScore b = WikiAppreciationScore.fromHalfStarUnits(9);
        WikiAppreciationScore c = WikiAppreciationScore.fromStars(new BigDecimal("4.0"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }
}
