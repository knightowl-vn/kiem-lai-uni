package com.universe.novel.infrastructure.markdown;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommonMarkReaderCanonicalBlocksTest {

    private final CommonMarkNovelMarkdownRenderer renderer = new CommonMarkNovelMarkdownRenderer();
    private final CommonMarkReaderCanonicalBlocks canonicalBlocks = new CommonMarkReaderCanonicalBlocks(renderer);
    private final CommonMarkReaderSemanticBlocks semanticBlocks = new CommonMarkReaderSemanticBlocks();

    @Test
    @DisplayName("Trích xuất đoạn văn với inline formatting: strong, em, link text, inline code")
    void shouldExtractParagraphWithInlineFormatting() {
        String markdown = "Đây là **in đậm**, *in nghiêng*, [liên kết](https://example.com) và `mã inline`.";

        List<CommonMarkReaderCanonicalBlocks.CanonicalBlock> blocks = canonicalBlocks.extract(markdown);

        assertThat(blocks).hasSize(1);
        CommonMarkReaderCanonicalBlocks.CanonicalBlock block = blocks.get(0);
        assertThat(block.blockKey()).startsWith("blk-");
        assertThat(block.canonicalText()).isEqualTo("Đây là in đậm, in nghiêng, liên kết và mã inline.");

        String html = renderer.renderToHtml(markdown);
        assertThat(html).contains("data-reader-block-key=\"" + block.blockKey() + "\"");
    }

    @Test
    @DisplayName("SoftLineBreak giữ nguyên ký tự xuống dòng \\n theo DOM HTML thay vì khoảng trắng ' '")
    void shouldPreserveSoftLineBreakAsNewline() {
        String markdown = "Dòng một\nDòng hai";

        List<CommonMarkReaderCanonicalBlocks.CanonicalBlock> blocks = canonicalBlocks.extract(markdown);

        assertThat(blocks).hasSize(1);
        CommonMarkReaderCanonicalBlocks.CanonicalBlock block = blocks.get(0);
        assertThat(block.canonicalText()).isEqualTo("Dòng một\nDòng hai");
        assertThat(block.canonicalText()).contains("\n");
        assertThat(block.canonicalText()).doesNotContain("Dòng một Dòng hai");
    }

    @Test
    @DisplayName("HardLineBreak (hai khoảng trắng cuối dòng) giữ nguyên xuống dòng \\n từ HTML <br />\\n")
    void shouldPreserveHardLineBreakAsNewline() {
        String markdown = "Dòng một  \nDòng hai";

        List<CommonMarkReaderCanonicalBlocks.CanonicalBlock> blocks = canonicalBlocks.extract(markdown);

        assertThat(blocks).hasSize(1);
        CommonMarkReaderCanonicalBlocks.CanonicalBlock block = blocks.get(0);
        assertThat(block.canonicalText()).isEqualTo("Dòng một\nDòng hai");
    }

    @Test
    @DisplayName("Fenced code block bảo toàn thụt lề, ngắt dòng và giải mã thực thể HTML")
    void shouldPreserveFencedCodeBlockExactContent() {
        String markdown = """
                ```java
                public void hello() {
                    System.out.println("Hello & World");
                }
                ```
                """;

        List<CommonMarkReaderCanonicalBlocks.CanonicalBlock> blocks = canonicalBlocks.extract(markdown);

        assertThat(blocks).hasSize(1);
        CommonMarkReaderCanonicalBlocks.CanonicalBlock block = blocks.get(0);
        assertThat(block.canonicalText()).isEqualTo("""
                public void hello() {
                    System.out.println("Hello & World");
                }
                """);
    }

    @Test
    @DisplayName("Tight list items trích xuất từng item với canonicalText chính xác")
    void shouldExtractTightListItems() {
        String markdown = """
                * Mục một
                * Mục hai
                * Mục ba
                """;

        List<CommonMarkReaderCanonicalBlocks.CanonicalBlock> blocks = canonicalBlocks.extract(markdown);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).canonicalText()).isEqualTo("Mục một");
        assertThat(blocks.get(1).canonicalText()).isEqualTo("Mục hai");
        assertThat(blocks.get(2).canonicalText()).isEqualTo("Mục ba");
    }

    @Test
    @DisplayName("Loose list items trích xuất nguyên vẹn khoảng trắng/newline DOM của <p> lồng trong <li>")
    void shouldExtractLooseListItemsPreservingDomNewlines() {
        String markdown = """
                * Mục một

                * Mục hai
                """;

        List<CommonMarkReaderCanonicalBlocks.CanonicalBlock> blocks = canonicalBlocks.extract(markdown);

        assertThat(blocks).hasSize(2);
        // Loose lists in CommonMark render: <li>\n<p>Mục một</p>\n</li>
        assertThat(blocks.get(0).canonicalText()).isEqualTo("\nMục một\n");
        assertThat(blocks.get(1).canonicalText()).isEqualTo("\nMục hai\n");
    }

    @Test
    @DisplayName("Table row trích xuất canonicalText KHÔNG rỗng và giữ nguyên text của các ô")
    void shouldExtractTableRowsWithNonEmptyCanonicalText() {
        String markdown = """
                | Cột 1 | Cột 2 |
                |---|---|
                | Dữ liệu A | Dữ liệu B |
                | Dữ liệu C | Dữ liệu D |
                """;

        List<CommonMarkReaderCanonicalBlocks.CanonicalBlock> blocks = canonicalBlocks.extract(markdown);

        // 1 header row + 2 body rows = 3 table rows
        assertThat(blocks).hasSize(3);
        for (CommonMarkReaderCanonicalBlocks.CanonicalBlock block : blocks) {
            assertThat(block.blockKey()).startsWith("blk-");
            assertThat(block.canonicalText()).isNotBlank();
        }

        assertThat(blocks.get(0).canonicalText()).isEqualTo("\nCột 1\nCột 2\n");
        assertThat(blocks.get(1).canonicalText()).isEqualTo("\nDữ liệu A\nDữ liệu B\n");
        assertThat(blocks.get(2).canonicalText()).isEqualTo("\nDữ liệu C\nDữ liệu D\n");
    }

    @Test
    @DisplayName("Giải mã HTML entities đúng như DOM trình duyệt")
    void shouldDecodeHtmlEntitiesNaturally() {
        String markdown = "Cá &amp; Khoai tây &gt; Bánh mì &lt; Táo &quot;Ngon&quot;";

        List<CommonMarkReaderCanonicalBlocks.CanonicalBlock> blocks = canonicalBlocks.extract(markdown);

        assertThat(blocks).hasSize(1);
        CommonMarkReaderCanonicalBlocks.CanonicalBlock block = blocks.get(0);
        assertThat(block.canonicalText()).isEqualTo("Cá & Khoai tây > Bánh mì < Táo \"Ngon\"");
    }

    @Test
    @DisplayName("Cross-contract: Khóa blockKey trích xuất từ HTML trùng khớp 100% với Reader semantic blocks pipeline")
    void shouldMatchBlockKeysWithExistingReaderSemanticPipeline() {
        String markdown = """
                # Tiêu đề chương một

                Đoạn văn mở đầu với **điểm nhấn** và một [liên kết](https://example.com).

                Dòng thơ thứ nhất
                Dòng thơ thứ hai

                ```go
                func main() {
                    println("hi")
                }
                ```

                * Điểm 1
                * Điểm 2

                | A | B |
                |---|---|
                | 1 | 2 |
                """;

        List<CommonMarkReaderCanonicalBlocks.CanonicalBlock> canonical = canonicalBlocks.extract(markdown);
        var semanticDoc = semanticBlocks.parse(markdown);

        List<String> canonicalKeys = canonical.stream()
                .map(CommonMarkReaderCanonicalBlocks.CanonicalBlock::blockKey)
                .toList();
        List<String> semanticKeys = semanticDoc.blocks().stream()
                .map(CommonMarkReaderSemanticBlocks.SemanticBlock::blockKey)
                .toList();

        assertThat(canonicalKeys).isNotEmpty();
        assertThat(canonicalKeys).containsExactlyElementsOf(semanticKeys);
    }

    @Test
    @DisplayName("Trả về danh sách rỗng khi markdown null hoặc rỗng")
    void shouldReturnEmptyListForNullOrBlankMarkdown() {
        assertThat(canonicalBlocks.extract(null)).isEmpty();
        assertThat(canonicalBlocks.extract("")).isEmpty();
        assertThat(canonicalBlocks.extract("   \n\t  ")).isEmpty();
    }
}
