package com.universe.novel.infrastructure.markdown;

import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.reader.render.ReaderNarrationMarkdownRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class CommonMarkReaderNarrationMarkdownRenderer implements ReaderNarrationMarkdownRenderer {
    private final CommonMarkNarrationSourceBlocks sourceBlocks = new CommonMarkNarrationSourceBlocks();
    private final NovelMarkdownRenderer fallback;

    public CommonMarkReaderNarrationMarkdownRenderer(NovelMarkdownRenderer fallback) {
        this.fallback = fallback;
    }

    @Override
    public String renderToHtml(String markdown, Map<Integer, List<UUID>> segmentIdsByBlock) {
        if (segmentIdsByBlock == null || segmentIdsByBlock.isEmpty()) return fallback.renderToHtml(markdown);
        var source = sourceBlocks.parse(markdown);
        // Validate the entire block mapping before annotating any node.
        if (segmentIdsByBlock.size() != source.blocks().size()) return fallback.renderToHtml(markdown);
        Map<Node, String> attributesByNode = new IdentityHashMap<>();
        for (int index = 0; index < source.blocks().size(); index++) {
            List<UUID> ids = segmentIdsByBlock.get(index);
            if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null)
                    || ids.stream().distinct().count() != ids.size()) return fallback.renderToHtml(markdown);
            attributesByNode.put(source.blocks().get(index).node(), ids.stream().map(UUID::toString).collect(Collectors.joining(" ")));
        }
        source.document().accept(new AbstractVisitor() {
            @Override public void visit(HtmlBlock node) { node.unlink(); }
            @Override public void visit(HtmlInline node) { node.unlink(); }
        });
        return HtmlRenderer.builder().extensions(List.of(TablesExtension.create()))
                .escapeHtml(true).sanitizeUrls(true)
                .attributeProviderFactory(context -> (node, tagName, attributes) -> {
                    String ids = attributesByNode.get(node);
                    // CommonMark renders code blocks as both pre and code; annotate the source block once.
                    boolean codeBlock = node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock;
                    if (ids != null && (!codeBlock || "pre".equals(tagName))) {
                        attributes.put("data-narration-segment-ids", ids);
                    }
                }).build().render(source.document());
    }
}
