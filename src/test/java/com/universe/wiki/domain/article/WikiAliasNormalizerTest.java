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

    @Test
    @DisplayName("Chuẩn hóa Unicode NFC: Chuỗi NFD và NFC cho cùng một từ tiếng Việt tạo ra normalizedAlias giống hệt nhau")
    void normalizeHandlesCanonicallyEquivalentUnicodeForms() {
        String nfc = "Trần Bình An";
        String nfd = java.text.Normalizer.normalize(nfc, java.text.Normalizer.Form.NFD);

        // Verify NFD and NFC are different raw byte sequences
        assertThat(nfd).isNotEqualTo(nfc);

        // Verify cleanDisplayAlias produces identical NFC output
        assertThat(WikiAliasNormalizer.cleanDisplayAlias(nfd))
                .isEqualTo(WikiAliasNormalizer.cleanDisplayAlias(nfc))
                .isEqualTo(nfc);

        // Verify normalize produces identical normalized alias
        assertThat(WikiAliasNormalizer.normalize(nfd))
                .isEqualTo(WikiAliasNormalizer.normalize(nfc))
                .isEqualTo("trần bình an");
    }

    @Test
    @DisplayName("Chuẩn hóa các cụm từ tiếng Việt phức tạp ở dạng NFD (Tiểu phu tử, Trần kiếm tiên)")
    void normalizeComplexVietnameseNfdStrings() {
        String nfc1 = "Tiểu Phu Tử";
        String nfd1 = java.text.Normalizer.normalize(nfc1, java.text.Normalizer.Form.NFD);
        assertThat(WikiAliasNormalizer.normalize(nfd1)).isEqualTo("tiểu phu tử");

        String nfc2 = "Trần Kiếm Tiên";
        String nfd2 = java.text.Normalizer.normalize(nfc2, java.text.Normalizer.Form.NFD);
        assertThat(WikiAliasNormalizer.normalize(nfd2)).isEqualTo("trần kiếm tiên");
    }
}