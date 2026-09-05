package com.universe.novel.infrastructure.markdown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommonMarkChapterNarrationBlockExtractorTest {

    private CommonMarkChapterNarrationBlockExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new CommonMarkChapterNarrationBlockExtractor();
    }

    @Test
    @DisplayName("Blank or null Markdown returns empty list")
    void blankMarkdownReturnsEmptyList() {
        assertThat(extractor.extractBlocks(null)).isEmpty();
        assertThat(extractor.extractBlocks("")).isEmpty();
        assertThat(extractor.extractBlocks("   \n\t  \n   ")).isEmpty();
        assertThat(extractor.extractBlocks("---\n\n***\n\n___")).isEmpty();
    }

    @Test
    @DisplayName("Strips formatting-only Markdown while preserving ordered semantic blocks")
    void stripsFormattingWhilePreservingOrderedSemanticBlocks() {
        String markdown = """
                # Tiết 1: Đào Hoa Thôn

                Đoạn văn có chứa **chữ in đậm** và *chữ in nghiêng*, cùng với `inline code`.

                > Đây là lời trích dẫn trong hộp khối.

                * Danh sách mục thứ nhất
                * Danh sách mục thứ hai

                ---

                Đoạn văn cuối cùng kết thúc chương.
                """;

        List<String> blocks = extractor.extractBlocks(markdown);

        assertThat(blocks).containsExactly(
                "Tiết 1: Đào Hoa Thôn",
                "Đoạn văn có chứa chữ in đậm và chữ in nghiêng, cùng với inline code.",
                "Đây là lời trích dẫn trong hộp khối.",
                "Danh sách mục thứ nhất",
                "Danh sách mục thứ hai",
                "Đoạn văn cuối cùng kết thúc chương."
        );
    }

    @Test
    @DisplayName("Link text is spoken but URL destination is omitted")
    void linkTextIsSpokenButUrlIsOmitted() {
        String markdown = "Truy cập [Bách Khoa Toàn Thư Kiếm Lai](https://kiemlai.com/wiki/main) để tra cứu thông tin.";

        List<String> blocks = extractor.extractBlocks(markdown);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isEqualTo("Truy cập Bách Khoa Toàn Thư Kiếm Lai để tra cứu thông tin.");
        assertThat(blocks.get(0)).doesNotContain("https://kiemlai.com");
    }

    @Test
    @DisplayName("Images and raw HTML are completely omitted from speakable blocks")
    void imagesAndRawHtmlOmitted() {
        String markdown = """
                ![Bản đồ Ly Châu](https://kiemlai.com/images/map.jpg)

                <div class="note">Nội dung không đọc</div>

                <script>alert('xss')</script>

                Trần Bình An cất bước lên đường.
                """;

        List<String> blocks = extractor.extractBlocks(markdown);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isEqualTo("Trần Bình An cất bước lên đường.");
    }
}
