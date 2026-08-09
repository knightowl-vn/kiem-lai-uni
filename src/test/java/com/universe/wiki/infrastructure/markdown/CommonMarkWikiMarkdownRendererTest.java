package com.universe.wiki.infrastructure.markdown;

import com.universe.wiki.application.article.render
        .RenderedWikiContent;
import com.universe.wiki.application.article.render
        .WikiTocItem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommonMarkWikiMarkdownRendererTest {

    private CommonMarkWikiMarkdownRenderer
            renderer;

    @BeforeEach
    void setUp() {
        renderer =
                new CommonMarkWikiMarkdownRenderer();
    }

    @Test
    @DisplayName(
            "Render Markdown cơ bản thành HTML"
    )
    void shouldRenderMarkdownToHtml() {
        String markdown =
                """
                ## Tổng quan

                Trần Bình An là **nhân vật chính**
                của Kiếm Lai.
                """;

        RenderedWikiContent result =
                renderer.render(
                        markdown
                );

        assertThat(result.html())
                .contains(
                        "<h2"
                )
                .contains(
                        "Tổng quan"
                )
                .contains(
                        "<strong>nhân vật chính</strong>"
                );
    }

    @Test
    @DisplayName(
            "Sinh TOC từ H2 và H3"
    )
    void shouldGenerateTableOfContentsFromH2AndH3() {
        String markdown =
                """
                ## Tu hành và năng lực

                ### Cảnh giới

                ### Công pháp

                ## Quan hệ nhân vật

                ### Sư môn
                """;

        RenderedWikiContent result =
                renderer.render(
                        markdown
                );

        assertThat(
                result.tableOfContents()
        ).containsExactly(
                new WikiTocItem(
                        2,
                        "Tu hành và năng lực",
                        "tu-hanh-va-nang-luc"
                ),
                new WikiTocItem(
                        3,
                        "Cảnh giới",
                        "canh-gioi"
                ),
                new WikiTocItem(
                        3,
                        "Công pháp",
                        "cong-phap"
                ),
                new WikiTocItem(
                        2,
                        "Quan hệ nhân vật",
                        "quan-he-nhan-vat"
                ),
                new WikiTocItem(
                        3,
                        "Sư môn",
                        "su-mon"
                )
        );
    }

    @Test
    @DisplayName(
            "Sinh anchor tiếng Việt cho heading"
    )
    void shouldGenerateVietnameseHeadingAnchor() {
        RenderedWikiContent result =
                renderer.render(
                        """
                        ## Xuất thân và bối cảnh

                        ### Đạo tâm

                        ### Phi kiếm / Bội kiếm
                        """
                );

        assertThat(result.html())
                .contains(
                        "id=\"xuat-than-va-boi-canh\""
                )
                .contains(
                        "id=\"dao-tam\""
                )
                .contains(
                        "id=\"phi-kiem-boi-kiem\""
                );
    }

    @Test
    @DisplayName(
            "Heading trùng tên phải có anchor duy nhất"
    )
    void shouldGenerateUniqueAnchorForDuplicateHeadings() {
        RenderedWikiContent result =
                renderer.render(
                        """
                        ## Đặc điểm

                        Nội dung thứ nhất.

                        ## Đặc điểm

                        Nội dung thứ hai.

                        ## Đặc điểm

                        Nội dung thứ ba.
                        """
                );

        assertThat(
                result.tableOfContents()
        ).containsExactly(
                new WikiTocItem(
                        2,
                        "Đặc điểm",
                        "dac-diem"
                ),
                new WikiTocItem(
                        2,
                        "Đặc điểm",
                        "dac-diem-2"
                ),
                new WikiTocItem(
                        2,
                        "Đặc điểm",
                        "dac-diem-3"
                )
        );

        assertThat(result.html())
                .contains(
                        "id=\"dac-diem\""
                )
                .contains(
                        "id=\"dac-diem-2\""
                )
                .contains(
                        "id=\"dac-diem-3\""
                );
    }

    @Test
    @DisplayName(
            "H4 có anchor nhưng không xuất hiện trong TOC"
    )
    void shouldExcludeH4FromTableOfContents() {
        RenderedWikiContent result =
                renderer.render(
                        """
                        ## Tu hành

                        ### Công pháp

                        #### Bí mật công pháp
                        """
                );

        assertThat(
                result.tableOfContents()
        ).containsExactly(
                new WikiTocItem(
                        2,
                        "Tu hành",
                        "tu-hanh"
                ),
                new WikiTocItem(
                        3,
                        "Công pháp",
                        "cong-phap"
                )
        );

        assertThat(result.html())
                .contains(
                        "id=\"bi-mat-cong-phap\""
                );
    }

    @Test
    @DisplayName(
            "Markdown null hoặc rỗng trả về nội dung rỗng"
    )
    void shouldReturnEmptyContentForBlankMarkdown() {
        RenderedWikiContent nullResult =
                renderer.render(
                        null
                );

        RenderedWikiContent blankResult =
                renderer.render(
                        "   "
                );

        assertThat(nullResult.html())
                .isEmpty();

        assertThat(
                nullResult.tableOfContents()
        ).isEmpty();

        assertThat(blankResult.html())
                .isEmpty();

        assertThat(
                blankResult.tableOfContents()
        ).isEmpty();
    }

    @Test
    @DisplayName(
            "Raw HTML trong Markdown không được thực thi trực tiếp"
    )
    void shouldEscapeRawHtml() {
        RenderedWikiContent result =
                renderer.render(
                        """
                        ## Test

                        <script>alert('xss')</script>
                        """
                );

        assertThat(result.html())
                .doesNotContain(
                        "<script>"
                )
                .contains(
                        "&lt;script&gt;"
                );
    }
    
    @Test
    @DisplayName(
            "Render ảnh Markdown thành thẻ img"
    )
    void shouldRenderMarkdownImage() {
        RenderedWikiContent result =
                renderer.render(
                        """
                        ## Ngoại hình

                        ![Trần Bình An](https://example.com/tran-binh-an.webp)
                        """
                );

        assertThat(result.html())
                .contains(
                        "<img"
                )
                .contains(
                        "src=\"https://example.com/tran-binh-an.webp\""
                )
                .contains(
                        "alt=\"Trần Bình An\""
                );
    }

    @Test
    @DisplayName(
            "Không render URL ảnh javascript nguy hiểm"
    )
    void shouldSanitizeUnsafeImageUrl() {
        RenderedWikiContent result =
                renderer.render(
                        """
                        ![Ảnh](javascript:alert('xss'))
                        """
                );

        assertThat(result.html())
                .doesNotContain(
                        "javascript:"
                );
    }
}