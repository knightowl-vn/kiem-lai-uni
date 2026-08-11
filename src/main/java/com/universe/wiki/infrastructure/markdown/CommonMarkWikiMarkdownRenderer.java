package com.universe.wiki.infrastructure.markdown;

import com.universe.wiki.application.article.render.RenderedWikiContent;
import com.universe.wiki.application.article.render.WikiMarkdownRenderer;
import com.universe.wiki.application.article.render.WikiTocItem;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Emphasis;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.commonmark.renderer.text.TextContentRenderer;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class CommonMarkWikiMarkdownRenderer implements WikiMarkdownRenderer {

	private final List<Extension> extensions;

	private final Parser parser;

	private final TextContentRenderer textContentRenderer;

	private static final String CLEAR_WRAP_MARKER = "[[WIKI_CLEAR]]";

	public CommonMarkWikiMarkdownRenderer() {

		this.extensions = List.of(TablesExtension.create());

		this.parser = Parser.builder().extensions(extensions).build();

		this.textContentRenderer = TextContentRenderer.builder().build();
	}

	@Override
	public RenderedWikiContent render(String markdown) {

		if (markdown == null || markdown.isBlank()) {
			return RenderedWikiContent.empty();
		}

		Node document = parser.parse(markdown);

		/*
		 * Wiki không hỗ trợ raw HTML.
		 */
		removeRawHtml(document);

		/*
		 * Chuyển các ảnh Wiki có metadata thành block <figure>.
		 */
		transformWikiImageBlocks(document);

		transformWikiClearMarkers(document);

		Map<Heading, String> headingAnchors = new IdentityHashMap<>();

		List<WikiTocItem> tableOfContents = new ArrayList<>();

		collectHeadings(document, headingAnchors, tableOfContents);

		HtmlRenderer htmlRenderer = createHtmlRenderer(headingAnchors);

		String html = htmlRenderer.render(document);

		return new RenderedWikiContent(html, tableOfContents);
	}

	/*
	 * ===================================================== RAW HTML
	 * =====================================================
	 */

	private void removeRawHtml(Node document) {

		document.accept(new AbstractVisitor() {

			@Override
			public void visit(HtmlInline htmlInline) {
				htmlInline.unlink();
			}

			@Override
			public void visit(HtmlBlock htmlBlock) {
				htmlBlock.unlink();
			}
		});
	}

	/*
	 * ===================================================== WIKI IMAGE
	 * TRANSFORMATION =====================================================
	 */

	private void transformWikiImageBlocks(Node document) {

		List<Paragraph> imageParagraphs = new ArrayList<>();

		/*
		 * Không sửa tree ngay trong lúc visitor đang chạy.
		 *
		 * Thu thập trước rồi transform sau.
		 */
		document.accept(new AbstractVisitor() {

			@Override
			public void visit(Paragraph paragraph) {

				if (
				        findLeadingWikiImage(
				                paragraph
				        ) != null
				) {

					imageParagraphs.add(paragraph);

					return;
				}

				visitChildren(paragraph);
			}
		});

		for (Paragraph paragraph : imageParagraphs) {

			transformWikiImageParagraph(paragraph);
		}
	}

	/*
	 * ===================================================== CLEAR IMAGE WRAPPING
	 * =====================================================
	 */

	private void transformWikiClearMarkers(Node document) {

		List<Paragraph> clearParagraphs = new ArrayList<>();

		/*
		 * Thu thập trước.
		 *
		 * Không sửa AST ngay trong lúc visitor đang duyệt.
		 */
		document.accept(new AbstractVisitor() {

			@Override
			public void visit(Paragraph paragraph) {

				if (isWikiClearMarker(paragraph)) {

					clearParagraphs.add(paragraph);

					return;
				}

				visitChildren(paragraph);
			}
		});

		for (Paragraph paragraph : clearParagraphs) {

			WikiClearBlock clearBlock = new WikiClearBlock();

			paragraph.insertBefore(clearBlock);

			paragraph.unlink();
		}
	}

	private boolean isWikiClearMarker(Paragraph paragraph) {

		Node firstChild = paragraph.getFirstChild();

		/*
		 * Chỉ nhận marker khi paragraph chứa đúng MỘT Text node:
		 *
		 * [[WIKI_CLEAR]]
		 *
		 * Nếu nằm giữa câu thì không xử lý.
		 */
		if (!(firstChild instanceof Text text)) {
			return false;
		}

		if (text.getNext() != null) {
			return false;
		}

		return CLEAR_WRAP_MARKER.equals(text.getLiteral().trim());
	}

	private Image findLeadingWikiImage(
	        Paragraph paragraph
	) {

	    Node firstChild =
	            paragraph.getFirstChild();


	    if (
	            !(firstChild instanceof Image image)
	    ) {
	        return null;
	    }


	    String title =
	            image.getTitle();


	    if (
	            title == null
	            || !title.startsWith(
	                    "wiki:"
	            )
	    ) {
	        return null;
	    }


	    /*
	     * Không yêu cầu ảnh phải là node duy nhất.
	     *
	     * Nếu phía sau ảnh còn text,
	     * transform sẽ tự tách text thành
	     * paragraph riêng.
	     */
	    return image;
	}
	private void transformWikiImageParagraph(
	        Paragraph paragraph
	) {

	    Image image =
	            findLeadingWikiImage(
	                    paragraph
	            );


	    if (image == null) {
	        return;
	    }


	    /*
	     * Có thể phía sau Image vẫn còn:
	     *
	     * SoftLineBreak
	     * Text
	     *
	     * vì Admin không để dòng trống.
	     */
	    Node trailingNode =
	            image.getNext();


	    WikiImageMetadata metadata =
	            parseWikiImageMetadata(
	                    image.getTitle()
	            );


	    /*
	     * Caption theo format:
	     *
	     * ![ảnh](...)
	     *
	     * *caption*
	     *
	     * chỉ được lấy khi paragraph ảnh
	     * thực sự không chứa text phía sau.
	     */
	    Paragraph captionParagraph =
	            trailingNode == null
	                    ? findCaptionParagraph(
	                            paragraph.getNext()
	                    )
	                    : null;


	    String caption =
	            captionParagraph == null
	                    ? null
	                    : extractCaptionText(
	                            captionParagraph
	                    );


	    WikiImageBlock imageBlock =
	            new WikiImageBlock(
	                    metadata.size(),
	                    metadata.layout(),
	                    caption
	            );


	    /*
	     * Figure nằm tại vị trí paragraph cũ.
	     */
	    paragraph.insertBefore(
	            imageBlock
	    );


	    /*
	     * =====================================================
	     * TEXT ĐANG DÍNH CHUNG VỚI ẢNH
	     * =====================================================
	     *
	     * Ví dụ:
	     *
	     * ![ảnh](...)
	     * Cuộc sống hiện đại...
	     *
	     * CommonMark có thể parse thành:
	     *
	     * Paragraph
	     *   Image
	     *   SoftLineBreak
	     *   Text
	     *
	     * Ta tách Text thành Paragraph mới.
	     */
	    if (
	            trailingNode != null
	    ) {

	        Paragraph trailingParagraph =
	                new Paragraph();


	        imageBlock.insertAfter(
	                trailingParagraph
	        );


	        Node current =
	                trailingNode;


	        boolean firstTrailingNode =
	                true;


	        while (
	                current != null
	        ) {

	            Node next =
	                    current.getNext();


	            current.unlink();


	            /*
	             * Không giữ newline đầu tiên,
	             * vì paragraph mới tự tạo boundary rồi.
	             */
	            if (
	                    firstTrailingNode
	                    && (
	                        current instanceof SoftLineBreak
	                        || current instanceof HardLineBreak
	                    )
	            ) {

	                firstTrailingNode =
	                        false;

	                current =
	                        next;

	                continue;
	            }


	            trailingParagraph.appendChild(
	                    current
	            );


	            firstTrailingNode =
	                    false;


	            current =
	                    next;
	        }


	        /*
	         * Trường hợp phía sau ảnh chỉ có newline
	         * nhưng không có text thật.
	         */
	        if (
	                trailingParagraph
	                        .getFirstChild()
	                == null
	        ) {

	            trailingParagraph.unlink();
	        }
	    }


	    /*
	     * Chuyển Image node vào figure.
	     */
	    image.unlink();


	    imageBlock.appendChild(
	            image
	    );


	    /*
	     * Paragraph cũ không còn cần nữa.
	     */
	    paragraph.unlink();


	    /*
	     * Caption cũ giờ đã nằm trong figure.
	     */
	    if (
	            captionParagraph != null
	    ) {

	        captionParagraph.unlink();
	    }
	}

	private Paragraph findCaptionParagraph(Node possibleCaption) {

		if (!(possibleCaption instanceof Paragraph paragraph)) {
			return null;
		}

		Node firstChild = paragraph.getFirstChild();

		if (!(firstChild instanceof Emphasis emphasis)) {
			return null;
		}

		/*
		 * Caption phải là:
		 *
		 * *caption*
		 *
		 * và không chứa text khác ngoài emphasis.
		 */
		if (emphasis.getNext() != null) {
			return null;
		}

		return paragraph;
	}

	private String extractCaptionText(Paragraph paragraph) {

		return textContentRenderer.render(paragraph).trim();
	}

	/*
	 * ===================================================== IMAGE METADATA
	 * =====================================================
	 */

	private WikiImageMetadata parseWikiImageMetadata(String title) {

		String size = "medium";

		String layout = "block-center";

		String legacyAlign = null;

		if (title == null || !title.startsWith("wiki:")) {
			return new WikiImageMetadata(size, layout);
		}

		String metadata = title.substring("wiki:".length());

		String[] parts = metadata.split(";");

		for (String part : parts) {

			String[] pair = part.split("=", 2);

			if (pair.length != 2) {
				continue;
			}

			String key = pair[0].trim();

			String value = pair[1].trim();

			if ("size".equals(key) && isAllowedImageSize(value)) {

				size = value;
			}

			if ("layout".equals(key) && isAllowedImageLayout(value)) {

				layout = value;
			}

			/*
			 * Tương thích Markdown ảnh cũ.
			 */
			if ("align".equals(key) && isAllowedLegacyAlign(value)) {

				legacyAlign = value;
			}
		}

		/*
		 * Chỉ dùng align cũ nếu Markdown chưa có layout mới.
		 */
		boolean hasNewLayout = metadata.contains("layout=");

		if (!hasNewLayout && legacyAlign != null) {

			layout = switch (legacyAlign) {

			case "left" -> "block-left";

			case "right" -> "block-right";

			default -> "block-center";
			};
		}

		/*
		 * Wrap + full width không có ý nghĩa, vì sẽ không còn chỗ cho text.
		 */
		if ("full".equals(size) && isWrappingLayout(layout)) {

			size = "large";
		}

		return new WikiImageMetadata(size, layout);
	}

	private boolean isAllowedImageSize(String value) {

		return "small".equals(value) || "medium".equals(value) || "large".equals(value) || "full".equals(value);
	}

	private boolean isAllowedImageLayout(String value) {

		return "block-left".equals(value) || "block-center".equals(value) || "block-right".equals(value)
				|| "wrap-left".equals(value) || "wrap-right".equals(value);
	}

	private boolean isAllowedLegacyAlign(String value) {

		return "left".equals(value) || "center".equals(value) || "right".equals(value);
	}

	private boolean isWrappingLayout(String layout) {

		return "wrap-left".equals(layout) || "wrap-right".equals(layout);
	}

	/*
	 * ===================================================== HEADINGS / TOC
	 * =====================================================
	 */

	private void collectHeadings(Node document, Map<Heading, String> headingAnchors,
			List<WikiTocItem> tableOfContents) {

		Map<String, Integer> anchorCounters = new HashMap<>();

		document.accept(new AbstractVisitor() {

			@Override
			public void visit(Heading heading) {

				String title = extractHeadingText(heading);

				String baseAnchor = slugifyHeading(title);

				String anchor = createUniqueAnchor(baseAnchor, anchorCounters);

				headingAnchors.put(heading, anchor);

				if (heading.getLevel() == 2 || heading.getLevel() == 3) {

					tableOfContents.add(new WikiTocItem(heading.getLevel(), title, anchor));
				}

				visitChildren(heading);
			}
		});
	}

	private String extractHeadingText(Heading heading) {

		return textContentRenderer.render(heading).trim();
	}

	private String createUniqueAnchor(String baseAnchor, Map<String, Integer> anchorCounters) {

		int occurrence = anchorCounters.merge(baseAnchor, 1, Integer::sum);

		if (occurrence == 1) {
			return baseAnchor;
		}

		return baseAnchor + "-" + occurrence;
	}

	/*
	 * ===================================================== HTML RENDERER
	 * =====================================================
	 */

	private HtmlRenderer createHtmlRenderer(Map<Heading, String> headingAnchors) {

		return HtmlRenderer.builder().extensions(extensions)

				/*
				 * Defense in depth.
				 */
				.escapeHtml(true)

				/*
				 * javascript:... không được dùng cho link và image.
				 */
				.sanitizeUrls(true)

				/*
				 * Custom figure renderer.
				 */
				.nodeRendererFactory(WikiImageBlockNodeRenderer::new)

				/*
				 * Heading id + class cho img Wiki.
				 */
				.attributeProviderFactory(context -> new WikiAttributeProvider(headingAnchors))

				.build();
	}

	/*
	 * ===================================================== HEADING SLUG
	 * =====================================================
	 */

	private String slugifyHeading(String heading) {

		if (heading == null || heading.isBlank()) {
			return "section";
		}

		String normalized = heading.replace('đ', 'd').replace('Đ', 'D');

		normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD);

		normalized = normalized.replaceAll("\\p{M}+", "");

		normalized = normalized.toLowerCase(Locale.ROOT);

		normalized = normalized.replaceAll("[^a-z0-9]+", "-");

		normalized = normalized.replaceAll("^-+|-+$", "");

		if (normalized.isBlank()) {
			return "section";
		}

		return normalized;
	}

	/*
	 * ===================================================== CUSTOM IMAGE BLOCK
	 * =====================================================
	 */

	private static final class WikiImageBlock extends CustomBlock {

		private final String size;

		private final String layout;

		private final String caption;

		private WikiImageBlock(String size, String layout, String caption) {

			this.size = size;

			this.layout = layout;

			this.caption = caption;
		}

		private String getSize() {
			return size;
		}

		private String getLayout() {
			return layout;
		}

		private String getCaption() {
			return caption;
		}
	}

	private static final class WikiClearBlock extends CustomBlock {
	}

	private record WikiImageMetadata(String size, String layout) {
	}

	/*
	 * ===================================================== CUSTOM FIGURE RENDERER
	 * =====================================================
	 */

	private static final class WikiImageBlockNodeRenderer implements NodeRenderer {

		private final HtmlNodeRendererContext context;

		private final HtmlWriter html;

		private WikiImageBlockNodeRenderer(HtmlNodeRendererContext context) {

			this.context = context;

			this.html = context.getWriter();
		}

		@Override
		public Set<Class<? extends Node>> getNodeTypes() {

			return Set.of(WikiImageBlock.class, WikiClearBlock.class);
		}

		@Override
		public void render(Node node) {
			
			if (
			        node instanceof WikiClearBlock
			) {

			    html.line();


			    html.tag(
			            "div",
			            Map.of(
			                    "class",
			                    "wiki-clear-wrap",
			                    "aria-hidden",
			                    "true"
			            )
			    );


			    html.tag(
			            "/div"
			    );


			    html.line();

			    return;
			}

			WikiImageBlock block = (WikiImageBlock) node;

			Map<String, String> attributes = Map.of("class",
					"wiki-media " + "wiki-media--" + block.getSize() + " " + "wiki-media--" + block.getLayout());

			html.line();

			html.tag("figure", attributes);

			Node image = block.getFirstChild();

			if (image != null) {

				/*
				 * Dùng default Image renderer của CommonMark để giữ sanitizeUrls.
				 */
				context.render(image);
			}

			if (block.getCaption() != null && !block.getCaption().isBlank()) {

				html.tag("figcaption");

				/*
				 * html.text tự escape caption.
				 */
				html.text(block.getCaption());

				html.tag("/figcaption");
			}

			html.tag("/figure");

			html.line();
		}
	}

	/*
	 * ===================================================== ATTRIBUTES
	 * =====================================================
	 */

	private static final class WikiAttributeProvider implements AttributeProvider {

		private final Map<Heading, String> headingAnchors;

		private WikiAttributeProvider(Map<Heading, String> headingAnchors) {

			this.headingAnchors = headingAnchors;
		}

		@Override
		public void setAttributes(Node node, String tagName, Map<String, String> attributes) {

			if (node instanceof Heading heading) {

				String anchor = headingAnchors.get(heading);

				if (anchor != null) {

					attributes.put("id", anchor);
				}

				return;
			}

			if (node instanceof Image image) {

				String title = image.getTitle();

				if (title == null || !title.startsWith("wiki:")) {
					return;
				}

				/*
				 * Size/layout nằm trên figure, img chỉ cần class để Preview biết đây là ảnh có
				 * thể chỉnh.
				 */
				attributes.put("class", "wiki-content-image");

				/*
				 * Không hiện metadata Wiki dưới dạng browser tooltip.
				 */
				attributes.remove("title");
			}
		}
	}
}