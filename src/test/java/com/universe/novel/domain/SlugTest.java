package com.universe.novel.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlugTest {

        @Test
        @DisplayName("Chấp nhận slug hợp lệ")
        void shouldAcceptValidSlug() {
                Slug slug = new Slug(
                                "tran-binh-an");

                assertThat(slug.value())
                                .isEqualTo(
                                                "tran-binh-an");
        }

        @Test
        @DisplayName("Chuẩn hóa slug thành chữ thường")
        void shouldNormalizeSlugToLowercase() {
                Slug slug = new Slug(
                                "Tran-Binh-An");

                assertThat(slug.value())
                                .isEqualTo(
                                                "tran-binh-an");
        }

        @Test
        @DisplayName("Loại bỏ khoảng trắng ở hai đầu slug")
        void shouldTrimSlug() {
                Slug slug = new Slug(
                                "  tran-binh-an  ");

                assertThat(slug.value())
                                .isEqualTo(
                                                "tran-binh-an");
        }

        @Test
        @DisplayName("Hai slug bằng nhau sau khi chuẩn hóa")
        void shouldBeEqualAfterNormalization() {
                Slug first = new Slug(
                                "  Tran-Binh-An  ");

                Slug second = new Slug(
                                "tran-binh-an");

                assertThat(first)
                                .isEqualTo(second);

                assertThat(first.hashCode())
                                .isEqualTo(
                                                second.hashCode());
        }

        @Test
        @DisplayName("toString trả về giá trị slug đã chuẩn hóa")
        void shouldReturnNormalizedValueFromToString() {
                Slug slug = new Slug(
                                "  Volume-1  ");

                assertThat(slug.toString())
                                .isEqualTo(
                                                "volume-1");
        }

        @Test
        @DisplayName("Từ chối slug null")
        void shouldRejectNullSlug() {
                assertThatThrownBy(() -> new Slug(null))
                                .isInstanceOf(
                                                NullPointerException.class)
                                .hasMessage(
                                                "Slug không được để trống.");
        }

        @Test
        @DisplayName("Chấp nhận slug có đúng 180 ký tự")
        void shouldAcceptSlugAtMaxLength() {
                String maxLengthSlug = "a".repeat(180);

                Slug slug = new Slug(maxLengthSlug);

                assertThat(slug.value())
                                .hasSize(180);
        }

        @Test
        @DisplayName("Từ chối slug rỗng")
        void shouldRejectBlankSlug() {
                assertThatThrownBy(() -> new Slug("   "))
                                .isInstanceOf(
                                                IllegalArgumentException.class)
                                .hasMessage(
                                                "Slug không được để trống.");
        }

        @Test
        @DisplayName("Từ chối slug dài hơn 180 ký tự")
        void shouldRejectSlugLongerThanMaxLength() {
                String tooLong = "a".repeat(181);

                assertThatThrownBy(() -> new Slug(tooLong))
                                .isInstanceOf(
                                                IllegalArgumentException.class)
                                .hasMessage(
                                                "Slug không được vượt quá 180 ký tự.");
        }

        @Test
        @DisplayName("Từ chối slug chứa khoảng trắng")
        void shouldRejectSlugContainingSpaces() {
                assertThatThrownBy(() -> new Slug(
                                "tran binh an"))
                                .isInstanceOf(
                                                IllegalArgumentException.class)
                                .hasMessageContaining(
                                                "Slug chỉ được chứa");
        }

        @Test
        @DisplayName("Từ chối slug có dấu tiếng Việt")
        void shouldRejectVietnameseDiacritics() {
                assertThatThrownBy(() -> new Slug(
                                "trần-bình-an"))
                                .isInstanceOf(
                                                IllegalArgumentException.class)
                                .hasMessageContaining(
                                                "Slug chỉ được chứa");
        }

        @Test
        @DisplayName("Từ chối slug có dấu gạch ngang ở đầu")
        void shouldRejectSlugStartingWithHyphen() {
                assertThatThrownBy(() -> new Slug(
                                "-tran-binh-an"))
                                .isInstanceOf(
                                                IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Từ chối slug có dấu gạch ngang ở cuối")
        void shouldRejectSlugEndingWithHyphen() {
                assertThatThrownBy(() -> new Slug(
                                "tran-binh-an-"))
                                .isInstanceOf(
                                                IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Từ chối slug có hai dấu gạch ngang liên tiếp")
        void shouldRejectConsecutiveHyphens() {
                assertThatThrownBy(() -> new Slug(
                                "tran--binh-an"))
                                .isInstanceOf(
                                                IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Từ chối slug chứa dấu gạch dưới hoặc ký tự đặc biệt")
        void shouldRejectUnderscoreAndSpecialCharacters() {
                assertThatThrownBy(() -> new Slug(
                                "abc_def"))
                                .isInstanceOf(
                                                IllegalArgumentException.class)
                                .hasMessageContaining(
                                                "Slug chỉ được chứa");

                assertThatThrownBy(() -> new Slug(
                                "abc@def"))
                                .isInstanceOf(
                                                IllegalArgumentException.class)
                                .hasMessageContaining(
                                                "Slug chỉ được chứa");
        }
}
