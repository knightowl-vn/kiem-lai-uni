package com.universe.wiki.application.article.delete;

import java.util.UUID;

public record DeleteWikiArticleCommand(
        UUID articleId
) {
}