package com.universe.novel.application.locator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChapterTitleSearchNormalizerTest {

    @Test
    @DisplayName("Normalizes Vietnamese diacritics and d/đ accurately")
    void shouldNormalizeVietnameseText() {
        assertThat(ChapterTitleSearchNormalizer.normalize("Đại Đạo Triều Thiên"))
                .isEqualTo("dai dao trieu thien");

        assertThat(ChapterTitleSearchNormalizer.normalize("  Kiếm   Lai  —   Chương  1  "))
                .isEqualTo("kiem lai — chuong 1");

        assertThat(ChapterTitleSearchNormalizer.normalize("HỔ BÁO XUẤT SƠN"))
                .isEqualTo("ho bao xuat son");

        assertThat(ChapterTitleSearchNormalizer.normalize("ĐỖ QUYÊN"))
                .isEqualTo("do quyen");
    }

    @Test
    @DisplayName("Handles null and empty strings in normalize")
    void shouldHandleNullAndEmptyNormalize() {
        assertThat(ChapterTitleSearchNormalizer.normalize(null)).isEmpty();
        assertThat(ChapterTitleSearchNormalizer.normalize("")).isEmpty();
        assertThat(ChapterTitleSearchNormalizer.normalize("   ")).isEmpty();
    }

    @Test
    @DisplayName("Escapes LIKE wildcards: %, _, \\ with correct ordering")
    void shouldEscapeLikeWildcards() {
        assertThat(ChapterTitleSearchNormalizer.escapeLikeWildcards("100%"))
                .isEqualTo("100\\%");

        assertThat(ChapterTitleSearchNormalizer.escapeLikeWildcards("chapter_1"))
                .isEqualTo("chapter\\_1");

        assertThat(ChapterTitleSearchNormalizer.escapeLikeWildcards("path\\to"))
                .isEqualTo("path\\\\to");

        assertThat(ChapterTitleSearchNormalizer.escapeLikeWildcards("%_\\"))
                .isEqualTo("\\%\\_\\\\");

        assertThat(ChapterTitleSearchNormalizer.escapeLikeWildcards(null))
                .isEmpty();
    }

    @Test
    @DisplayName("Folds all đ and Đ occurrences to d and D deterministically")
    void shouldFoldD() {
        assertThat(ChapterTitleSearchNormalizer.foldD("đại đạo"))
                .isEqualTo("dại dạo");

        assertThat(ChapterTitleSearchNormalizer.foldD("Đông Đoài"))
                .isEqualTo("Dông Doài");

        assertThat(ChapterTitleSearchNormalizer.foldD("Dạ Đạo"))
                .isEqualTo("Dạ Dạo");

        assertThat(ChapterTitleSearchNormalizer.foldD("Kiếm Lai"))
                .isEqualTo("Kiếm Lai");

        assertThat(ChapterTitleSearchNormalizer.foldD(null))
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Đại Đạo | Đại Đạo | 1",
            "Đại Đạo Triều Thiên | dai dao | 2",
            "Vấn Đại Đạo | dai dao | 3",
            "Thiên Hạ Đệ Nhất Kiếm | Đệ Nhất | 3",
            "Chương 10: Khởi Đầu | chuong 10 | 2",
            "Khởi Đầu | khoi dau | 1"
    })
    @DisplayName("Determines match rank correctly: 1=exact, 2=prefix, 3=contains")
    void shouldDetermineTitleRank(String title, String query, int expectedRank) {
        int rank = ChapterTitleSearchNormalizer.determineTitleRank(title, query);
        assertThat(rank).isEqualTo(expectedRank);
    }
}
