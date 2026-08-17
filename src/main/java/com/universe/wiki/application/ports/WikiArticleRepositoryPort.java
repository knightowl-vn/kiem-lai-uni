package com.universe.wiki.application.ports;

import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;

import java.util.Optional;
import java.util.UUID;

public interface WikiArticleRepositoryPort {

    Optional<WikiArticle> findById(
            UUID articleId
    );

    Optional<WikiArticle> findByArticleTypeAndSlug(
            ArticleType articleType,
            Slug slug
    );

    boolean existsByArticleTypeAndSlug(
            ArticleType articleType,
            Slug slug
    );

    void save(
            WikiArticle article
    );
    void deleteById(
            UUID articleId
    );
}