package com.universe.novel.infrastructure.markdown;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommonMarkReaderNarrationMarkdownRendererTest {
    private final CommonMarkNovelMarkdownRenderer generic = new CommonMarkNovelMarkdownRenderer();
    private final CommonMarkReaderNarrationMarkdownRenderer renderer = new CommonMarkReaderNarrationMarkdownRenderer(generic);
    private final CommonMarkChapterNarrationBlockExtractor extractor = new CommonMarkChapterNarrationBlockExtractor();
    private final UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void semanticOrderingMatchesExtractorAcrossSupportedNestedStructures() {
        String markdown = "# Heading\n\nParagraph\n\n> Quote\n\n- Outer\n  - Nested\n- Other\n\n"
                + "| H1 | H2 |\n| --- | --- |\n| A | B |\n\n```java\ncode();\n```\n\n    indented\n";
        assertThat(extractor.extractBlocks(markdown)).containsExactly(
                "Heading", "Paragraph", "Quote", "Outer Nested", "Other", "H1, H2", "A, B", "code();", "indented");
        Map<Integer, List<UUID>> mapping = new LinkedHashMap<>();
        for (int index = 0; index < 9; index++) mapping.put(index, List.of(new UUID(0, index + 1)));
        String html = renderer.renderToHtml(markdown, mapping);
        String[] tags = {"h1", "p", "p", "li", "li", "tr", "tr", "pre", "pre"};
        for (int index = 0; index < tags.length; index++) {
            assertThat(html).contains("<" + tags[index] + " data-narration-segment-ids=\"" + new UUID(0, index + 1) + "\">");
        }
        assertThat(html.split("data-narration-segment-ids=", -1)).hasSize(10);
        assertThat(withoutAnchors(html)).isEqualTo(generic.renderToHtml(markdown));
    }

    @Test
    void duplicateTextAndManyToManyMembershipUseOrdinals() {
        String markdown = "Repeated.\n\nRepeated.\n\nOther.";
        String html = renderer.renderToHtml(markdown, Map.of(0, List.of(a), 1, List.of(a, b), 2, List.of(b)));
        assertThat(html).contains("<p data-narration-segment-ids=\"" + a + "\">Repeated.</p>",
                "<p data-narration-segment-ids=\"" + a + " " + b + "\">Repeated.</p>",
                "<p data-narration-segment-ids=\"" + b + "\">Other.</p>");
        assertThat(withoutAnchors(html)).isEqualTo(generic.renderToHtml(markdown));
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "missing", "extra", "invalidOrdinal", "emptyIds", "duplicateIds"})
    void invalidBlockMapFallsBackCompletely(String defect) {
        Map<Integer, List<UUID>> mapping = new LinkedHashMap<>(Map.of(0, List.of(a), 1, List.of(b)));
        switch (defect) {
            case "empty" -> mapping.clear();
            case "missing" -> mapping.remove(1);
            case "extra" -> mapping.put(2, List.of(a));
            case "invalidOrdinal" -> { mapping.remove(1); mapping.put(-1, List.of(b)); }
            case "emptyIds" -> mapping.put(1, List.of());
            case "duplicateIds" -> mapping.put(1, List.of(b, b));
        }
        String markdown = "One.\n\nTwo.";
        assertThat(renderer.renderToHtml(markdown, mapping)).isEqualTo(generic.renderToHtml(markdown))
                .doesNotContain("data-narration");
    }

    @Test
    void annotatedRenderingPreservesFormattingEscapingAndSanitization() {
        String markdown = "<script>bad()</script>\n\n**Bold** and _emphasis_ <b>tag</b> & < text. "
                + "[unsafe](javascript:alert(1)) ![image](https://example.test/a.png)\n\n---\n\n```\n<b>code</b>\n```";
        assertThat(extractor.extractBlocks(markdown)).hasSize(2);
        String html = renderer.renderToHtml(markdown, Map.of(0, List.of(a), 1, List.of(b)));
        assertThat(withoutAnchors(html)).isEqualTo(generic.renderToHtml(markdown));
        assertThat(html).contains("<strong>Bold</strong>", "<em>emphasis</em>", "&lt;b&gt;code&lt;/b&gt;")
                .doesNotContain("<script>", "<b>tag</b>", "href=\"javascript:");
    }

    private String withoutAnchors(String html) {
        return html.replaceAll(" data-narration-segment-ids=\"[^\"]*\"", "");
    }
}
