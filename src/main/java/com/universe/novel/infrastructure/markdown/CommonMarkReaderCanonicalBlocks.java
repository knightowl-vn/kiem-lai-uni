package com.universe.novel.infrastructure.markdown;

import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Novel infrastructure component that extracts canonical Reader blocks
 * from actual production Reader HTML rendered by {@link NovelMarkdownRenderer}.
 *
 * <p>Canonical text accurately models browser {@code element.textContent} by
 * concatenating descendant {@link TextNode} contents in DOM order without
 * whitespace normalization.</p>
 */
@Component
public class CommonMarkReaderCanonicalBlocks {

    public record CanonicalBlock(String blockKey, String canonicalText) {
        public CanonicalBlock {
            Objects.requireNonNull(blockKey, "blockKey cannot be null");
            Objects.requireNonNull(canonicalText, "canonicalText cannot be null");
        }
    }

    private final NovelMarkdownRenderer markdownRenderer;

    public CommonMarkReaderCanonicalBlocks(NovelMarkdownRenderer markdownRenderer) {
        this.markdownRenderer = Objects.requireNonNull(markdownRenderer, "markdownRenderer cannot be null");
    }

    /**
     * Renders the given markdown using the production Reader renderer and extracts
     * ordered canonical blocks with exact browser-textContent semantics.
     *
     * @param markdown the chapter markdown
     * @return ordered immutable list of canonical blocks
     */
    public List<CanonicalBlock> extract(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        String html = markdownRenderer.renderToHtml(markdown);
        return extractFromHtml(html);
    }

    private List<CanonicalBlock> extractFromHtml(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }

        Document doc = Jsoup.parseBodyFragment(html);
        Elements blockElements = doc.select("[data-reader-block-key]");
        if (blockElements.isEmpty()) {
            return List.of();
        }

        List<CanonicalBlock> blocks = new ArrayList<>(blockElements.size());
        for (Element blockEl : blockElements) {
            String blockKey = blockEl.attr("data-reader-block-key");
            if (blockKey == null || blockKey.isBlank()) {
                continue;
            }

            StringBuilder sb = new StringBuilder();
            collectText(blockEl, sb);
            blocks.add(new CanonicalBlock(blockKey, sb.toString()));
        }

        return List.copyOf(blocks);
    }

    private static void collectText(Node node, StringBuilder sb) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode textNode) {
                sb.append(textNode.getWholeText());
            } else {
                collectText(child, sb);
            }
        }
    }
}
