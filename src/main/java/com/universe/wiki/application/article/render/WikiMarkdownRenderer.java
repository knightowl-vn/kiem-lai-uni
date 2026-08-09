package com.universe.wiki.application.article.render;

public interface WikiMarkdownRenderer {

    RenderedWikiContent render(
            String markdown
    );
}