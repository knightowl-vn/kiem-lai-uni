package com.universe.screen.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenSlugGeneratorTest {

    @ParameterizedTest(name = "Title: \"{0}\" -> Slug: \"{1}\"")
    @CsvSource({
            "Kiếm Lai Phần 1, kiem-lai-phan-1",
            "Đấu Phá Thương Khung, dau-pha-thuong-khung",
            "Đại Chúa Tể, dai-chua-te",
            "Thế Giới Hoàn Mỹ, the-gioi-hoan-my",
            "Tuyết Trung Hãn Đao Hành, tuyet-trung-han-dao-hanh",
            "Phàm Nhân Tu Tiên: Tiên Giới Thiên, pham-nhan-tu-tien-tien-gioi-thien",
            "   Đạo Tình   , dao-tinh",
            "Movie #123 (Full HD), movie-123-full-hd",
            "A---B---C, a-b-c"
    })
    @DisplayName("generates valid URL slugs with Vietnamese diacritics including đ/Đ normalized")
    void shouldNormalizeVietnameseTitles(String inputTitle, String expectedSlug) {
        String slug = ScreenSlugGenerator.generate(inputTitle);
        assertThat(slug).isEqualTo(expectedSlug);
        assertThat(ScreenSlugValidator.validate(slug)).isEqualTo(expectedSlug);
    }

    @Test
    @DisplayName("falls back to default slug when title is null or blank")
    void shouldFallbackWhenTitleIsBlank() {
        assertThat(ScreenSlugGenerator.generate(null)).isEqualTo("screen-title");
        assertThat(ScreenSlugGenerator.generate("   ")).isEqualTo("screen-title");
    }

    @Test
    @DisplayName("falls back to default slug when title has only foreign or non-alphanumeric symbols")
    void shouldFallbackWhenTitleHasNoAlphanumeric() {
        assertThat(ScreenSlugGenerator.generate("剑来")).isEqualTo("screen-title");
        assertThat(ScreenSlugGenerator.generate("!@#$%^&*()")).isEqualTo("screen-title");
    }

    @Test
    @DisplayName("caps slug length to maximum 180 characters without trailing hyphen")
    void shouldCapSlugLengthAt180() {
        String longTitle = "kiem lai ".repeat(30);
        String slug = ScreenSlugGenerator.generate(longTitle);

        assertThat(slug.length()).isLessThanOrEqualTo(180);
        assertThat(slug).doesNotEndWith("-");
        assertThat(ScreenSlugValidator.validate(slug)).isEqualTo(slug);
    }
}
