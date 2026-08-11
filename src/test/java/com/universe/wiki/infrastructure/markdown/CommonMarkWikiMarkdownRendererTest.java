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
            "Raw HTML bị loại khỏi nội dung Wiki"
    )
    void shouldRemoveRawHtml() {

        RenderedWikiContent result =
                renderer.render(
                        """
                        ## Test

                        <script>alert('xss')</script>

                        Nội dung hợp lệ.
                        """
                );


        assertThat(result.html())
                .doesNotContain(
                        "<script>"
                )
                .doesNotContain(
                        "&lt;script&gt;"
                )
                .doesNotContain(
                        "alert('xss')"
                )
                .contains(
                        "Nội dung hợp lệ."
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
    
    @Test
    @DisplayName(
            "Render ảnh Wiki thành figure có caption và layout"
    )
    void shouldRenderWikiImageAsFigure() {

        RenderedWikiContent result =
                renderer.render(
                        """
                        Nội dung phía trước.

                        ![Trần Bình An](https://example.com/tran-binh-an.webp "wiki:size=medium;layout=wrap-right")

                        *Trần Bình An tại Kiếm Khí Trường Thành*

                        Nội dung phía sau sẽ chạy cạnh ảnh.
                        """
                );


        assertThat(result.html())
                .contains(
                        "<figure"
                )
                .contains(
                        "wiki-media"
                )
                .contains(
                        "wiki-media--medium"
                )
                .contains(
                        "wiki-media--wrap-right"
                )
                .contains(
                        "<img"
                )
                .contains(
                        "class=\"wiki-content-image\""
                )
                .contains(
                        "<figcaption>"
                )
                .contains(
                        "Trần Bình An tại Kiếm Khí Trường Thành"
                );
    }


    @Test
    @DisplayName(
            "Metadata align cũ vẫn tương thích"
    )
    void shouldSupportLegacyImageAlignMetadata() {

        RenderedWikiContent result =
                renderer.render(
                        """
                        ![Ảnh cũ](https://example.com/legacy.webp "wiki:size=small;align=right")

                        *Ảnh cũ*
                        """
                );


        assertThat(result.html())
                .contains(
                        "wiki-media--small"
                )
                .contains(
                        "wiki-media--block-right"
                );
    }


    @Test
    @DisplayName(
            "Ảnh Markdown thường không bị biến thành Wiki figure"
    )
    void shouldKeepNormalMarkdownImageUnchanged() {

        RenderedWikiContent result =
                renderer.render(
                        """
                        ![Ảnh thường](https://example.com/image.webp)
                        """
                );


        assertThat(result.html())
                .contains(
                        "<img"
                )
                .doesNotContain(
                        "<figure"
                )
                .doesNotContain(
                        "wiki-media"
                );
    }
    
    @Test
    @DisplayName(
            "Marker WIKI_CLEAR phải kết thúc việc bọc chữ quanh ảnh"
    )
    void shouldRenderWikiClearWrapMarker() {

        RenderedWikiContent result =
                renderer.render(
                        """
                        ![Ảnh minh họa](https://example.com/image.webp "wiki:size=medium;layout=wrap-right")

                        *Chú thích ảnh*

                        Đoạn văn vẫn đang bọc quanh ảnh.

                        [[WIKI_CLEAR]]

                        Đoạn này phải bắt đầu bên dưới ảnh.
                        """
                );


        assertThat(result.html())
                .contains(
                        "wiki-media--wrap-right"
                )
                .contains(
                        "wiki-clear-wrap"
                )
                .contains(
                        "Đoạn này phải bắt đầu bên dưới ảnh."
                )
                .doesNotContain(
                        "[[WIKI_CLEAR]]"
                );
    }
    
    @Test
    @DisplayName(
            "WIKI_CLEAR nằm giữa câu không được xem là lệnh ngắt bọc"
    )
    void shouldNotTreatInlineClearMarkerAsDirective() {

        RenderedWikiContent result =
                renderer.render(
                        """
                        Đây là nội dung có [[WIKI_CLEAR]] nằm giữa câu.
                        """
                );


        assertThat(result.html())
                .contains(
                        "[[WIKI_CLEAR]]"
                )
                .doesNotContain(
                        "wiki-clear-wrap"
                );
    }
    @Test
    @DisplayName(
            "Ảnh Wiki vẫn áp dụng layout khi text không có dòng trống sau ảnh"
    )
    void shouldRenderWikiImageWithoutBlankLineAfterImage() {

        RenderedWikiContent result =
                renderer.render(
                        """
                        ![Ảnh](https://example.com/image.webp "wiki:size=small;layout=wrap-right")
                        Đoạn văn phải chạy bên trái ảnh.
                        """
                );


        assertThat(result.html())
                .contains(
                        "wiki-media--small"
                )
                .contains(
                        "wiki-media--wrap-right"
                )
                .contains(
                        "<figure"
                )
                .contains(
                        "Đoạn văn phải chạy bên trái ảnh."
                );
    }
    
}