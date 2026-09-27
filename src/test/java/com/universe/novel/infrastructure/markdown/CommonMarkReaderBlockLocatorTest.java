package com.universe.novel.infrastructure.markdown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CommonMarkReaderBlockLocatorTest {

    private CommonMarkNovelMarkdownRenderer normalRenderer;
    private CommonMarkReaderNarrationMarkdownRenderer narrationRenderer;
    private CommonMarkChapterNarrationBlockExtractor narrationExtractor;

    @BeforeEach
    void setUp() {
        normalRenderer = new CommonMarkNovelMarkdownRenderer();
        narrationRenderer = new CommonMarkReaderNarrationMarkdownRenderer(normalRenderer);
        narrationExtractor = new CommonMarkChapterNarrationBlockExtractor();
    }

    @Test
    @DisplayName("1. Determinism: Rendering identical Markdown repeatedly produces exactly the same ordered Reader block keys")
    void determinismProducesIdenticalBlockKeys() {
        String markdown = """
                # Tiêu Đề Chương

                Đoạn văn thứ nhất nói về mở đầu.

                Đoạn văn thứ hai tiếp tục câu chuyện.
                """;

        String html1 = normalRenderer.renderToHtml(markdown);
        String html2 = normalRenderer.renderToHtml(markdown);

        List<String> keys1 = extractBlockKeys(html1);
        List<String> keys2 = extractBlockKeys(html2);

        assertThat(keys1).hasSize(3);
        assertThat(keys1).isEqualTo(keys2);
        assertThat(html1).isEqualTo(html2);
    }

    @Test
    @DisplayName("2. Normal Reader vs Narration Reader: Produces identical Reader block keys in the same order")
    void normalAndNarrationReadersProduceSameBlockKeys() {
        String markdown = """
                # Tiêu Đề

                Đoạn văn A.

                > Đoạn văn trong trích dẫn.

                - Mục danh sách 1
                - Mục danh sách 2
                """;

        UUID seg1 = UUID.randomUUID();
        UUID seg2 = UUID.randomUUID();
        UUID seg3 = UUID.randomUUID();
        UUID seg4 = UUID.randomUUID();
        UUID seg5 = UUID.randomUUID();

        Map<Integer, List<UUID>> segmentMapping = Map.of(
                0, List.of(seg1),
                1, List.of(seg2),
                2, List.of(seg3),
                3, List.of(seg4),
                4, List.of(seg5)
        );

        String normalHtml = normalRenderer.renderToHtml(markdown);
        String narrationHtml = narrationRenderer.renderToHtml(markdown, segmentMapping);

        List<String> normalKeys = extractBlockKeys(normalHtml);
        List<String> narrationKeys = extractBlockKeys(narrationHtml);

        assertThat(normalKeys).hasSize(5);
        assertThat(narrationKeys).isEqualTo(normalKeys);

        // Narration HTML contains narration segment IDs while normal HTML does not
        assertThat(narrationHtml).contains("data-narration-segment-ids=\"" + seg1 + "\"");
        assertThat(normalHtml).doesNotContain("data-narration-segment-ids");
    }

    @Test
    @DisplayName("3. Unrelated insertion stability: An unchanged unique block B retains the exact same block key")
    void unrelatedInsertionDoesNotShiftUniqueBlockKey() {
        String v1Markdown = """
                Đoạn văn A.

                Đoạn văn B không đổi.

                Đoạn văn C.
                """;

        String v2Markdown = """
                Đoạn văn MỚI chèn ở đầu.

                Đoạn văn A.

                Đoạn văn B không đổi.

                Đoạn văn C.
                """;

        String v1Html = normalRenderer.renderToHtml(v1Markdown);
        String v2Html = normalRenderer.renderToHtml(v2Markdown);

        List<String> v1Keys = extractBlockKeys(v1Html);
        List<String> v2Keys = extractBlockKeys(v2Html);

        assertThat(v1Keys).hasSize(3);
        assertThat(v2Keys).hasSize(4);

        String blockBKeyV1 = v1Keys.get(1); // Block B is at index 1 in v1
        String blockBKeyV2 = v2Keys.get(2); // Block B is at index 2 in v2 (after NEW and A)

        assertThat(blockBKeyV2).isEqualTo(blockBKeyV1);
    }

    @Test
    @DisplayName("4. Duplicate text: Two identical blocks in the same chapter receive distinct deterministic keys")
    void duplicateBlocksReceiveDistinctDeterministicKeys() {
        String markdown = """
                Đoạn văn lặp lại y hệt.

                Đoạn văn khác ở giữa.

                Đoạn văn lặp lại y hệt.
                """;

        String html1 = normalRenderer.renderToHtml(markdown);
        String html2 = normalRenderer.renderToHtml(markdown);

        List<String> keys1 = extractBlockKeys(html1);
        List<String> keys2 = extractBlockKeys(html2);

        assertThat(keys1).hasSize(3);
        assertThat(keys1.get(0)).isNotEqualTo(keys1.get(2));
        assertThat(keys1.get(0)).endsWith("-1");
        assertThat(keys1.get(2)).endsWith("-2");

        // Both keys share the same base fingerprint
        String prefix1 = keys1.get(0).substring(0, keys1.get(0).lastIndexOf('-'));
        String prefix2 = keys1.get(2).substring(0, keys1.get(2).lastIndexOf('-'));
        assertThat(prefix1).isEqualTo(prefix2);

        // Deterministic across renders
        assertThat(keys1).isEqualTo(keys2);
    }

    @Test
    @DisplayName("5. Edited text: Changing the textual prose changes the block key")
    void editedBlockChangesBlockKey() {
        String originalMarkdown = "Đoạn văn gốc trước khi sửa.";
        String editedMarkdown = "Đoạn văn đã được tác giả sửa đổi.";

        String originalHtml = normalRenderer.renderToHtml(originalMarkdown);
        String editedHtml = normalRenderer.renderToHtml(editedMarkdown);

        String originalKey = extractBlockKeys(originalHtml).get(0);
        String editedKey = extractBlockKeys(editedHtml).get(0);

        assertThat(editedKey).isNotEqualTo(originalKey);
    }

    @Test
    @DisplayName("6. Semantic block boundaries: Applies key to leaf prose blocks and never duplicates container keys")
    void semanticBlockBoundariesApplyOnlyToLeafProse() {
        String markdown = """
                # Tiêu Đề

                Đoạn văn thông thường với **in đậm** và _in nghiêng_.

                > Đoạn văn trong khối trích dẫn.

                * Mục danh sách A
                * Mục danh sách B

                ```java
                System.out.println("Hello Code");
                ```

                | Cột 1 | Cột 2 |
                | --- | --- |
                | Hàng 1 | Dữ liệu 1 |
                | Hàng 2 | Dữ liệu 2 |
                """;

        String html = normalRenderer.renderToHtml(markdown);

        // Heading: exactly on <h1>
        assertThat(html).contains("<h1 data-reader-block-key=");
        // Paragraph: exactly on <p>
        assertThat(html).contains("<p data-reader-block-key=");
        // BlockQuote: <p> inside blockquote has key, <blockquote> does NOT
        assertThat(html).contains("<blockquote>\n<p data-reader-block-key=");
        assertThat(html).doesNotContain("<blockquote data-reader-block-key=");
        // List: <li> has key, <ul> does NOT
        assertThat(html).contains("<li data-reader-block-key=");
        assertThat(html).doesNotContain("<ul data-reader-block-key=");
        // Code: <pre> has key, <code> does NOT
        assertThat(html).contains("<pre data-reader-block-key=");
        assertThat(html).doesNotContain("<code data-reader-block-key=");
        // Table: <tr> has key, <table>, <thead>, <tbody> do NOT
        assertThat(html).contains("<tr data-reader-block-key=");
        assertThat(html).doesNotContain("<table data-reader-block-key=");
        assertThat(html).doesNotContain("<thead data-reader-block-key=");
        assertThat(html).doesNotContain("<tbody data-reader-block-key=");

        // Count: 1 heading + 1 paragraph + 1 quote-paragraph + 2 list items + 1 code block + 3 table rows (1 head + 2 body) = 9
        List<String> keys = extractBlockKeys(html);
        assertThat(keys).hasSize(9);
    }

    @Test
    @DisplayName("7. Formatting changes (markup only) do not change prose fingerprint")
    void inlineFormattingDoesNotChangeBlockKey() {
        String plain = "Đoạn văn với chữ quan trọng và đường dẫn.";
        String markedUp = "Đoạn văn với **chữ quan trọng** và [đường dẫn](https://example.com).";

        String plainHtml = normalRenderer.renderToHtml(plain);
        String markedUpHtml = normalRenderer.renderToHtml(markedUp);

        String plainKey = extractBlockKeys(plainHtml).get(0);
        String markedUpKey = extractBlockKeys(markedUpHtml).get(0);

        assertThat(markedUpKey).isEqualTo(plainKey);
    }

    @Test
    @DisplayName("8. Reader key is independent from Narration cleanup: collapsing excessive dots in Narration does not collapse Reader keys")
    void readerKeyIsIndependentFromNarrationCleanup() {
        String md1 = "Khoan.... đã.";
        String md2 = "Khoan... đã.";

        String html1 = normalRenderer.renderToHtml(md1);
        String html2 = normalRenderer.renderToHtml(md2);

        String key1 = extractBlockKeys(html1).get(0);
        String key2 = extractBlockKeys(html2).get(0);

        // Reader block keys MUST be different
        assertThat(key1).isNotEqualTo(key2);

        // Narration speakable text remains collapsed to "Khoan... đã." for both
        List<String> narration1 = narrationExtractor.extractBlocks(md1);
        List<String> narration2 = narrationExtractor.extractBlocks(md2);
        assertThat(narration1).containsExactly("Khoan... đã.");
        assertThat(narration2).containsExactly("Khoan... đã.");
    }

    @Test
    @DisplayName("9. Code indentation: Meaningful first-line indentation produces different Reader block keys")
    void codeIndentationPreservationAffectsBlockKey() {
        String code1 = """
                ```java
                  int a = 1;
                ```
                """;
        String code2 = """
                ```java
                int a = 1;
                ```
                """;

        String html1 = normalRenderer.renderToHtml(code1);
        String html2 = normalRenderer.renderToHtml(code2);

        String key1 = extractBlockKeys(html1).get(0);
        String key2 = extractBlockKeys(html2).get(0);

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("10. Table row cell boundaries: Different cell partitionings produce distinct Reader block keys")
    void tableRowCellBoundariesProduceDistinctBlockKeys() {
        String table1 = """
                | Col1 | Col2 |
                | --- | --- |
                | A, B | C |
                """;
        String table2 = """
                | Col1 | Col2 |
                | --- | --- |
                | A | B, C |
                """;

        String html1 = normalRenderer.renderToHtml(table1);
        String html2 = normalRenderer.renderToHtml(table2);

        // Row 1 is header (Col1, Col2), Row 2 is body row
        List<String> keys1 = extractBlockKeys(html1);
        List<String> keys2 = extractBlockKeys(html2);

        assertThat(keys1).hasSize(2);
        assertThat(keys2).hasSize(2);

        // Headers match
        assertThat(keys1.get(0)).isEqualTo(keys2.get(0));
        // Body rows have different cell partitionings and thus must produce different keys
        assertThat(keys1.get(1)).isNotEqualTo(keys2.get(1));

        // Narration speakable text for both body rows is identical ("A, B, C")
        List<String> narration1 = narrationExtractor.extractBlocks(table1);
        List<String> narration2 = narrationExtractor.extractBlocks(table2);
        assertThat(narration1.get(1)).isEqualTo("A, B, C");
        assertThat(narration2.get(1)).isEqualTo("A, B, C");
    }

    @Test
    @DisplayName("11. Code special whitespace: Indentation differing by preserved non-ASCII whitespace produces distinct Reader block keys")
    void codeSpecialWhitespaceProducesDistinctBlockKeys() {
        // code1 uses standard ASCII spaces, code2 uses non-breaking spaces (\u00A0)
        String code1 = "```java\n  int a = 1;\n```";
        String code2 = "```java\n\u00A0\u00A0int a = 1;\n```";

        String html1 = normalRenderer.renderToHtml(code1);
        String html2 = normalRenderer.renderToHtml(code2);

        String key1 = extractBlockKeys(html1).get(0);
        String key2 = extractBlockKeys(html2).get(0);

        assertThat(key1).isNotEqualTo(key2);
    }

    private List<String> extractBlockKeys(String html) {
        Pattern pattern = Pattern.compile("data-reader-block-key=\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(html);
        List<String> keys = new ArrayList<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }
}
