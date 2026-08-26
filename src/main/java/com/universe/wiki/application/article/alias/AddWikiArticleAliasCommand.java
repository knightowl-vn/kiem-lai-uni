package com.universe.wiki.application.article.alias;

import java.util.UUID;

public record AddWikiArticleAliasCommand(
        UUID articleId,
        String alias
) {
}