package com.universe.wiki.domain.article;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.universe.wiki.domain.article.Slug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlugTest {

    @Test
    @DisplayName(
            "Chuẩn hóa slug thành chữ thường"
    )
    void shouldNormalizeSlugToLowercase() {
        Slug slug =
                new Slug(
                        "Tran-Binh-An"
                );

        assertThat(slug.value())
                .isEqualTo(
                        "tran-binh-an"
                );
    }

    @Test
    @DisplayName(
            "Loại bỏ khoảng trắng ở hai đầu slug"
    )
    void shouldTrimSlug() {
        Slug slug =
                new Slug(
                        "  tran-binh-an  "
                );

        assertThat(slug.value())
                .isEqualTo(
                        "tran-binh-an"
                );
    }

    @Test
    @DisplayName(
            "Từ chối slug rỗng"
    )
    void shouldRejectBlankSlug() {
        assertThatThrownBy(() ->
                new Slug("   ")
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Slug không được để trống."
                );
    }

    @Test
    @DisplayName(
            "Từ chối slug chứa khoảng trắng"
    )
    void shouldRejectSlugContainingSpaces() {
        assertThatThrownBy(() ->
                new Slug(
                        "tran binh an"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "Slug chỉ được chứa"
                );
    }

    @Test
    @DisplayName(
            "Từ chối slug có dấu tiếng Việt"
    )
    void shouldRejectVietnameseDiacritics() {
        assertThatThrownBy(() ->
                new Slug(
                        "trần-bình-an"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "Slug chỉ được chứa"
                );
    }

    @Test
    @DisplayName(
            "Từ chối slug có dấu gạch ngang ở đầu"
    )
    void shouldRejectSlugStartingWithHyphen() {
        assertThatThrownBy(() ->
                new Slug(
                        "-tran-binh-an"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    @DisplayName(
            "Từ chối slug có hai dấu gạch ngang liên tiếp"
    )
    void shouldRejectConsecutiveHyphens() {
        assertThatThrownBy(() ->
                new Slug(
                        "tran--binh-an"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }
}