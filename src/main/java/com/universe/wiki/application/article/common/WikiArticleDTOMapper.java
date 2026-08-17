package com.universe.wiki.application.article.common;

import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.WikiArticle;

import java.util.Objects;

public final class WikiArticleDTOMapper {

    private WikiArticleDTOMapper() {
    }

    public static WikiArticleDTO toDTO(
            WikiArticle article
    ) {
        Objects.requireNonNull(
                article,
                "Wiki article không được để trống."
        );

        return new WikiArticleDTO(
                article.getId(),
                article.getTitle(),
                article.getSlug().value(),
                article.getArticleType().name(),
                article.getSummary(),
                article.getContent(),
                article.getStatus().name(),
                article.getCreatedBy(),
                article.getUpdatedBy(),
                article.getPublishedBy(),
                article.getArchivedBy(),
                article.getCreatedAt(),
                article.getUpdatedAt(),
                article.getPublishedAt(),
                article.getArchivedAt(),
                article.getAggregateVersion(),
                article.getContentVersion()
        );
    }
}