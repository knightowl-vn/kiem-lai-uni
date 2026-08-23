package com.universe.novel.infrastructure.markdown;

import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Novel-owned Markdown renderer.
 * Mirrors Wiki's CommonMark + GFM tables approach without Wiki-specific image/clear-wrap features.
 */
@Component
public class CommonMarkNovelMarkdownRenderer implements NovelMarkdownRenderer {

	private final Parser parser;

	private final HtmlRenderer htmlRenderer;

	public CommonMarkNovelMarkdownRenderer() {
		List<Extension> extensions = List.of(TablesExtension.create());

		this.parser = Parser.builder().extensions(extensions).build();

		this.htmlRenderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build();
	}

	@Override
	public String renderToHtml(String markdown) {
		if (markdown == null || markdown.isBlank()) {
			return "";
		}

		Node document = parser.parse(markdown);

		removeRawHtml(document);

		return htmlRenderer.render(document);
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
