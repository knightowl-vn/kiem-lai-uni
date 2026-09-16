package com.universe.novel.infrastructure.markdown;

import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;

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

/**
 * Novel-owned Markdown renderer with deterministic Reader block locators.
 * Mirrors Wiki's CommonMark + GFM tables approach and annotates canonical
 * data-reader-block-key attributes on semantic Reader blocks.
 */
@Component
public class CommonMarkNovelMarkdownRenderer implements NovelMarkdownRenderer {

	private final CommonMarkReaderSemanticBlocks semanticBlocks = new CommonMarkReaderSemanticBlocks();

	@Override
	public String renderToHtml(String markdown) {
		if (markdown == null || markdown.isBlank()) {
			return "";
		}

		var source = semanticBlocks.parse(markdown);

		removeRawHtml(source.document());

		Map<Node, String> blockKeysByNode = new IdentityHashMap<>();
		for (var block : source.blocks()) {
			blockKeysByNode.put(block.node(), block.blockKey());
		}

		return HtmlRenderer.builder()
				.extensions(List.of(TablesExtension.create()))
				.escapeHtml(true)
				.sanitizeUrls(true)
				.attributeProviderFactory(context -> (node, tagName, attributes) -> {
					String blockKey = blockKeysByNode.get(node);
					boolean codeBlock = node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock;
					if (blockKey != null && (!codeBlock || "pre".equals(tagName))) {
						attributes.put("data-reader-block-key", blockKey);
					}
				})
				.build()
				.render(source.document());
	}

	private void removeRawHtml(Node document) {
		document.accept(new AbstractVisitor() {
			@Override
			public void visit(HtmlBlock htmlBlock) {
				htmlBlock.unlink();
			}

			@Override
			public void visit(HtmlInline htmlInline) {
				htmlInline.unlink();
			}
		});
	}
}
