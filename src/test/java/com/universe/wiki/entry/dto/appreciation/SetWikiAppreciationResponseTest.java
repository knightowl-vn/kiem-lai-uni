package com.universe.wiki.entry.dto.appreciation;

import com.universe.wiki.application.appreciation.SetWikiAppreciationResult;
import com.universe.wiki.domain.appreciation.WikiAppreciationScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SetWikiAppreciationResponse Unit Tests")
class SetWikiAppreciationResponseTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("Làm tròn HALF_UP 1 chữ số thập phân cho displayAverage, bảo toàn nguyên vẹn BigDecimal average")
    void shouldFormatDisplayAverageAndPreserveAuthoritativeAverage() {
        // 4.75 -> "4.8"
        BigDecimal avg475 = new BigDecimal("4.75");
        SetWikiAppreciationResult result1 = new SetWikiAppreciationResult(
                ARTICLE_ID,
                WikiAppreciationScore.fromStars(new BigDecimal("5.0")),
                avg475,
                2L,
                true
        );
        SetWikiAppreciationResponse resp1 = SetWikiAppreciationResponse.from(result1);
        assertThat(resp1.displayAverage()).isEqualTo("4.8");
        assertThat(resp1.average()).isSameAs(avg475);
        assertThat(resp1.average()).isEqualTo(new BigDecimal("4.75"));

        // 4.65 -> "4.7"
        BigDecimal avg465 = new BigDecimal("4.65");
        SetWikiAppreciationResult result2 = new SetWikiAppreciationResult(
                ARTICLE_ID,
                WikiAppreciationScore.fromStars(new BigDecimal("4.5")),
                avg465,
                2L,
                true
        );
        SetWikiAppreciationResponse resp2 = SetWikiAppreciationResponse.from(result2);
        assertThat(resp2.displayAverage()).isEqualTo("4.7");
        assertThat(resp2.average()).isSameAs(avg465);
        assertThat(resp2.average()).isEqualTo(new BigDecimal("4.65"));

        // 5.0 -> "5.0"
        BigDecimal avg50 = new BigDecimal("5.0");
        SetWikiAppreciationResult result3 = new SetWikiAppreciationResult(
                ARTICLE_ID,
                WikiAppreciationScore.fromStars(new BigDecimal("5.0")),
                avg50,
                1L,
                true
        );
        SetWikiAppreciationResponse resp3 = SetWikiAppreciationResponse.from(result3);
        assertThat(resp3.displayAverage()).isEqualTo("5.0");
        assertThat(resp3.average()).isSameAs(avg50);
        assertThat(resp3.average()).isEqualTo(new BigDecimal("5.0"));

        // null average -> null displayAverage
        SetWikiAppreciationResult resultNull = new SetWikiAppreciationResult(
                ARTICLE_ID,
                WikiAppreciationScore.fromStars(new BigDecimal("4.0")),
                null,
                0L,
                false
        );
        SetWikiAppreciationResponse respNull = SetWikiAppreciationResponse.from(resultNull);
        assertThat(respNull.displayAverage()).isNull();
        assertThat(respNull.average()).isNull();
    }
}
