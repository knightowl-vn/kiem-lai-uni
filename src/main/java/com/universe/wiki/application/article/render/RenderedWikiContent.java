package com.universe.wiki.application.article.render;

import java.util.List;

public record RenderedWikiContent(
        String html,
        List<WikiTocItem> tableOfContents
) {

    public RenderedWikiContent {
        html =
                html == null
                        ? ""
                        : html;

        tableOfContents =
                tableOfContents == null
                        ? List.of()
                        : List.copyOf(
                                tableOfContents
                        );
    }

    public static RenderedWikiContent empty() {
        return new RenderedWikiContent(
                "",
                List.of()
        );
    }
}