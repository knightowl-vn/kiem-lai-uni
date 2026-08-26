package com.universe.wiki.domain.article;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class WikiAliasNormalizerTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t\n"})
    @DisplayName("Chuẩn hóa null hoặc blank alias trả về chuỗi rỗng")
    void normalizeBlankReturnsEmpty(String input) {
        assertThat(WikiAliasNormalizer.normalize(input)).isEmpty();
        assertThat(WikiAliasNormalizer.cleanDisplayAlias(input)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "'Trần Bình An', 'trần bình an'",
            "'  Tiểu   Phu   Tử  ', 'tiểu phu tử'",
            "'Trần tiên sinh', 'trần tiên sinh'",
            "'TRẦN KIẾM TIÊN', 'trần kiếm tiên'",
            "'Đại   Sư   Huynh', 'đại sư huynh'"
    })
    @DisplayName("Chuẩn hóa alias: trim, gộp khoảng trắng liên tiếp và chuyển thành lowercase")
    void normalizeProperlyFormatsAlias(String input, String expected) {
        assertThat(WikiAliasNormalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("cleanDisplayAlias giữ nguyên hoa/thường nhưng trim và gộp khoảng trắng")
    void cleanDisplayAliasPreservesCase() {
        assertThat(WikiAliasNormalizer.cleanDisplayAlias("  Tiểu   Phu   Tử  ")).isEqualTo("Tiểu Phu Tử");
    }
}