package com.universe.wiki.application.article.render;

public record WikiTocItem(
        int level,
        String title,
        String anchor
) {
}