package com.universe.wiki.application.article.unpublish;

import java.util.UUID;

public record UnpublishWikiArticleCommand(
        UUID articleId,
        String editSummary,
        UUID actorId
) {
}