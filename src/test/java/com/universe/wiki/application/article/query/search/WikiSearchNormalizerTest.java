package com.universe.wiki.application.article.query.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class WikiSearchNormalizerTest {

    @Test
    @DisplayName("cleanQuery handles null and blank input safely")
    void cleanQueryHandlesNullAndBlank() {
        assertThat(WikiSearchNormalizer.cleanQuery(null)).isEmpty();
        assertThat(WikiSearchNormalizer.cleanQuery("")).isEmpty();
        assertThat(WikiSearchNormalizer.cleanQuery("   ")).isEmpty();
        assertThat(WikiSearchNormalizer.cleanQuery("\t \n \r ")).isEmpty();
    }

    @Test
    @DisplayName("cleanQuery collapses consecutive whitespace and trims ends")
    void cleanQueryCollapsesWhitespace() {
        assertThat(WikiSearchNormalizer.cleanQuery("  Trần   Bình   An  ")).isEqualTo("Trần Bình An");
        assertThat(WikiSearchNormalizer.cleanQuery("Tiểu\t\tPhu\n\nTử")).isEqualTo("Tiểu Phu Tử");
    }

    @Test
    @DisplayName("cleanQuery clamps input longer than 200 characters")
    void cleanQueryClampsLength() {
        String longInput = "a".repeat(250);
        String cleaned = WikiSearchNormalizer.cleanQuery(longInput);
        assertThat(cleaned).hasSize(200);
        assertThat(cleaned).isEqualTo("a".repeat(200));
    }

    @Test
    @DisplayName("fold handles null and blank input safely")
    void foldHandlesNullAndBlank() {
        assertThat(WikiSearchNormalizer.fold(null)).isEmpty();
        assertThat(WikiSearchNormalizer.fold("")).isEmpty();
        assertThat(WikiSearchNormalizer.fold("   ")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "Trần Bình An, tran binh an",
            "Tran Binh An, tran binh an",
            "TRẦN BÌNH AN, tran binh an",
            "Tiểu Phu Tử, tieu phu tu",
            "Tieu Phu Tu, tieu phu tu",
            "Đạo Tổ, dao to",
            "Dao To, dao to",
            "ĐẠO TỔ, dao to",
            "đạo quán, dao quan",
            "Hạo Nhiên Khí, hao nhien khi",
            "Kiếm Lai, kiem lai"
    })
    @DisplayName("fold strips Vietnamese diacritics, folds Đ/đ to d, lowercases, and normalizes spaces")
    void foldEquivalence(String input, String expectedFolded) {
        assertThat(WikiSearchNormalizer.fold(input)).isEqualTo(expectedFolded);
    }

    @Test
    @DisplayName("fold verifies equivalence of accented and non-accented variants")
    void foldEquivalenceBetweenVariants() {
        assertThat(WikiSearchNormalizer.fold("Trần Bình An")).isEqualTo(WikiSearchNormalizer.fold("Tran Binh An"));
        assertThat(WikiSearchNormalizer.fold("Tiểu Phu Tử")).isEqualTo(WikiSearchNormalizer.fold("Tieu Phu Tu"));
        assertThat(WikiSearchNormalizer.fold("Đạo")).isEqualTo(WikiSearchNormalizer.fold("Dao"));
        assertThat(WikiSearchNormalizer.fold("đạo")).isEqualTo(WikiSearchNormalizer.fold("dao"));
        assertThat(WikiSearchNormalizer.fold("ĐẠO")).isEqualTo(WikiSearchNormalizer.fold("DAO"));
    }

    @Test
    @DisplayName("escapeLike escapes special LIKE metacharacters correctly")
    void escapeLikeSpecialCharacters() {
        assertThat(WikiSearchNormalizer.escapeLike(null)).isEmpty();
        assertThat(WikiSearchNormalizer.escapeLike("")).isEmpty();
        assertThat(WikiSearchNormalizer.escapeLike("normal")).isEqualTo("normal");
        assertThat(WikiSearchNormalizer.escapeLike("100% pure")).isEqualTo("100\\% pure");
        assertThat(WikiSearchNormalizer.escapeLike("prefix_match")).isEqualTo("prefix\\_match");
        assertThat(WikiSearchNormalizer.escapeLike("path\\to\\item")).isEqualTo("path\\\\to\\\\item");
        assertThat(WikiSearchNormalizer.escapeLike("%_\\")).isEqualTo("\\%\\_\\\\");
    }
}
