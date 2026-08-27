package com.universe.wiki.application.article.alias;

import java.util.UUID;

public record RemoveWikiArticleAliasCommand(
        UUID articleId,
        String alias
) {
}