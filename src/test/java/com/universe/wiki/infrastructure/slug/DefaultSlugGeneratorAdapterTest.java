package com.universe.wiki.infrastructure.slug;

import com.universe.wiki.domain.article.Slug;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultSlugGeneratorAdapterTest {

    private DefaultSlugGeneratorAdapter
            slugGeneratorAdapter;

    @BeforeEach
    void setUp() {
        slugGeneratorAdapter =
                new DefaultSlugGeneratorAdapter();
    }

    @Test
    @DisplayName(
            "Tạo slug từ tiêu đề tiếng Việt"
    )
    void shouldGenerateSlugFromVietnameseTitle() {
        Slug result =
                slugGeneratorAdapter.generate(
                        "Trần Bình An"
                );

        assertThat(result.value())
                .isEqualTo(
                        "tran-binh-an"
                );
    }

    @Test
    @DisplayName(
            "Chuyển ký tự đ thành d"
    )
    void shouldConvertVietnameseDCharacter() {
        Slug result =
                slugGeneratorAdapter.generate(
                        "Động Thiên Ly Châu"
                );

        assertThat(result.value())
                .isEqualTo(
                        "dong-thien-ly-chau"
                );
    }

    @Test
    @DisplayName(
            "Thay khoảng trắng và ký tự đặc biệt bằng dấu gạch ngang"
    )
    void shouldReplaceSpecialCharactersWithHyphen() {
        Slug result =
                slugGeneratorAdapter.generate(
                        "Kiếm Lai: Chương 1!"
                );

        assertThat(result.value())
                .isEqualTo(
                        "kiem-lai-chuong-1"
                );
    }

    @Test
    @DisplayName(
            "Không tạo nhiều dấu gạch ngang liên tiếp"
    )
    void shouldCollapseMultipleSeparators() {
        Slug result =
                slugGeneratorAdapter.generate(
                        "Trần   Bình --- An"
                );

        assertThat(result.value())
                .isEqualTo(
                        "tran-binh-an"
                );
    }

    @Test
    @DisplayName(
            "Loại bỏ khoảng trắng và ký tự đặc biệt ở hai đầu"
    )
    void shouldRemoveEdgeSeparators() {
        Slug result =
                slugGeneratorAdapter.generate(
                        "  -- Trần Bình An --  "
                );

        assertThat(result.value())
                .isEqualTo(
                        "tran-binh-an"
                );
    }

    @Test
    @DisplayName(
            "Từ chối nguồn tạo slug null"
    )
    void shouldRejectNullSource() {
        assertThatThrownBy(() ->
                slugGeneratorAdapter.generate(
                        null
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Nguồn tạo slug không được để trống."
                );
    }

    @Test
    @DisplayName(
            "Từ chối nguồn tạo slug rỗng"
    )
    void shouldRejectBlankSource() {
        assertThatThrownBy(() ->
                slugGeneratorAdapter.generate(
                        "   "
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Nguồn tạo slug không được để trống."
                );
    }

    @Test
    @DisplayName(
            "Từ chối tiêu đề không tạo được slug hợp lệ"
    )
    void shouldRejectSourceWithoutValidCharacters() {
        assertThatThrownBy(() ->
                slugGeneratorAdapter.generate(
                        "!@#$%^&*()"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Không thể tạo slug hợp lệ từ dữ liệu đã cung cấp."
                );
    }
}