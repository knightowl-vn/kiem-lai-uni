package com.universe.wiki.infrastructure.markdown;

import com.universe.wiki.application.article.render
        .RenderedWikiContent;
import com.universe.wiki.application.article.render
        .WikiMarkdownRenderer;
import com.universe.wiki.application.article.render
        .WikiTocItem;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables
        .TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html
        .AttributeProvider;
import org.commonmark.renderer.html
        .HtmlRenderer;
import org.commonmark.renderer.text
        .TextContentRenderer;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class CommonMarkWikiMarkdownRenderer
        implements WikiMarkdownRenderer {

    private final List<Extension> extensions;

    private final Parser parser;

    private final TextContentRenderer
            textContentRenderer;

    public CommonMarkWikiMarkdownRenderer() {
        this.extensions =
                List.of(
                        TablesExtension.create()
                );

        this.parser =
                Parser.builder()
                        .extensions(
                                extensions
                        )
                        .build();

        this.textContentRenderer =
                TextContentRenderer
                        .builder()
                        .build();
    }

    @Override
    public RenderedWikiContent render(
            String markdown
    ) {
        if (
                markdown == null
                || markdown.isBlank()
        ) {
            return RenderedWikiContent.empty();
        }

        Node document =
                parser.parse(
                        markdown
                );

        Map<Heading, String> headingAnchors =
                new IdentityHashMap<>();

        List<WikiTocItem> tableOfContents =
                new ArrayList<>();

        collectHeadings(
                document,
                headingAnchors,
                tableOfContents
        );

        HtmlRenderer htmlRenderer =
                createHtmlRenderer(
                        headingAnchors
                );

        String html =
                htmlRenderer.render(
                        document
                );

        return new RenderedWikiContent(
                html,
                tableOfContents
        );
    }

    private void collectHeadings(
            Node document,
            Map<Heading, String> headingAnchors,
            List<WikiTocItem> tableOfContents
    ) {
        Map<String, Integer> anchorCounters =
                new HashMap<>();

        document.accept(
                new AbstractVisitor() {

                    @Override
                    public void visit(
                            Heading heading
                    ) {
                        String title =
                                extractHeadingText(
                                        heading
                                );

                        String baseAnchor =
                                slugifyHeading(
                                        title
                                );

                        String anchor =
                                createUniqueAnchor(
                                        baseAnchor,
                                        anchorCounters
                                );

                        headingAnchors.put(
                                heading,
                                anchor
                        );

                        if (
                                heading.getLevel() == 2
                                || heading.getLevel() == 3
                        ) {
                            tableOfContents.add(
                                    new WikiTocItem(
                                            heading.getLevel(),
                                            title,
                                            anchor
                                    )
                            );
                        }

                        visitChildren(
                                heading
                        );
                    }
                }
        );
    }

    private String extractHeadingText(
            Heading heading
    ) {
        return textContentRenderer
                .render(
                        heading
                )
                .trim();
    }

    private String createUniqueAnchor(
            String baseAnchor,
            Map<String, Integer> anchorCounters
    ) {
        int occurrence =
                anchorCounters.merge(
                        baseAnchor,
                        1,
                        Integer::sum
                );

        if (occurrence == 1) {
            return baseAnchor;
        }

        return baseAnchor
                + "-"
                + occurrence;
    }

    private HtmlRenderer createHtmlRenderer(
            Map<Heading, String> headingAnchors
    ) {
        return HtmlRenderer.builder()
                .extensions(
                        extensions
                )

                /*
                 * Không cho raw HTML từ Markdown
                 * trở thành HTML thực.
                 */
                .escapeHtml(
                        true
                )

                /*
                 * Loại bỏ URL không an toàn
                 * như javascript:...
                 */
                .sanitizeUrls(
                        true
                )

                /*
                 * Gắn id cho các heading.
                 */
                .attributeProviderFactory(
                        context ->
                                new HeadingIdAttributeProvider(
                                        headingAnchors
                                )
                )
                .build();
    }

    private String slugifyHeading(
            String heading
    ) {
        if (
                heading == null
                || heading.isBlank()
        ) {
            return "section";
        }

        String normalized =
                heading
                        .replace(
                                'đ',
                                'd'
                        )
                        .replace(
                                'Đ',
                                'D'
                        );

        normalized =
                Normalizer.normalize(
                        normalized,
                        Normalizer.Form.NFD
                );

        normalized =
                normalized.replaceAll(
                        "\\p{M}+",
                        ""
                );

        normalized =
                normalized.toLowerCase(
                        Locale.ROOT
                );

        normalized =
                normalized.replaceAll(
                        "[^a-z0-9]+",
                        "-"
                );

        normalized =
                normalized.replaceAll(
                        "^-+|-+$",
                        ""
                );

        if (normalized.isBlank()) {
            return "section";
        }

        return normalized;
    }

    private static class HeadingIdAttributeProvider
            implements AttributeProvider {

        private final Map<Heading, String>
                headingAnchors;

        private HeadingIdAttributeProvider(
                Map<Heading, String> headingAnchors
        ) {
            this.headingAnchors =
                    headingAnchors;
        }

        @Override
        public void setAttributes(
                Node node,
                String tagName,
                Map<String, String> attributes
        ) {
            if (!(node instanceof Heading heading)) {
                return;
            }

            String anchor =
                    headingAnchors.get(
                            heading
                    );

            if (anchor == null) {
                return;
            }

            attributes.put(
                    "id",
                    anchor
            );
        }
    }
}