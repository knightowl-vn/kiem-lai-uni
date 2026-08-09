package com.universe.wiki.entry.web.support;

import com.universe.wiki.domain.article.ArticleType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticleTypePathMapperTest {

    private ArticleTypePathMapper
            mapper;

    @BeforeEach
    void setUp() {
        mapper =
                new ArticleTypePathMapper();
    }

    @Test
    @DisplayName(
            "Chuyển toàn bộ URL path thành ArticleType"
    )
    void shouldConvertPathToArticleType() {
        assertThat(
                mapper.fromPath("character")
        ).isEqualTo(
                ArticleType.CHARACTER
        );

        assertThat(
                mapper.fromPath("realm")
        ).isEqualTo(
                ArticleType.REALM
        );

        assertThat(
                mapper.fromPath("cultivation-path")
        ).isEqualTo(
                ArticleType.CULTIVATION_PATH
        );

        assertThat(
                mapper.fromPath("faction")
        ).isEqualTo(
                ArticleType.FACTION
        );

        assertThat(
                mapper.fromPath("item")
        ).isEqualTo(
                ArticleType.ITEM
        );

        assertThat(
                mapper.fromPath("technique")
        ).isEqualTo(
                ArticleType.TECHNIQUE
        );

        assertThat(
                mapper.fromPath("location")
        ).isEqualTo(
                ArticleType.LOCATION
        );

        assertThat(
                mapper.fromPath("world")
        ).isEqualTo(
                ArticleType.WORLD
        );

        assertThat(
                mapper.fromPath("timeline-event")
        ).isEqualTo(
                ArticleType.TIMELINE_EVENT
        );
    }

    @Test
    @DisplayName(
            "Chuẩn hóa chữ hoa và khoảng trắng trong path"
    )
    void shouldNormalizePath() {
        ArticleType result =
                mapper.fromPath(
                        "  TIMELINE-EVENT  "
                );

        assertThat(result)
                .isEqualTo(
                        ArticleType.TIMELINE_EVENT
                );
    }

    @Test
    @DisplayName(
            "Chuyển toàn bộ ArticleType thành URL path"
    )
    void shouldConvertArticleTypeToPath() {
        assertThat(
                mapper.toPath(
                        ArticleType.CHARACTER
                )
        ).isEqualTo(
                "character"
        );

        assertThat(
                mapper.toPath(
                        ArticleType.CULTIVATION_PATH
                )
        ).isEqualTo(
                "cultivation-path"
        );

        assertThat(
                mapper.toPath(
                        ArticleType.ITEM
                )
        ).isEqualTo(
                "item"
        );

        assertThat(
                mapper.toPath(
                        ArticleType.TIMELINE_EVENT
                )
        ).isEqualTo(
                "timeline-event"
        );
    }

    @Test
    @DisplayName(
            "Từ chối article type path không hợp lệ"
    )
    void shouldRejectInvalidPath() {
        assertThatThrownBy(() ->
                mapper.fromPath(
                        "unknown-type"
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Article type path không hợp lệ: "
                                + "unknown-type"
                );
    }

    @Test
    @DisplayName(
            "Từ chối article type path để trống"
    )
    void shouldRejectBlankPath() {
        assertThatThrownBy(() ->
                mapper.fromPath(
                        "   "
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Article type path không được để trống."
                );
    }
}