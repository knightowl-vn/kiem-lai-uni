package com.universe.wiki.application.ports;

import com.universe.wiki.application.article.cover.backfill.WikiReferencedCoverKeysetQuery;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;

import java.util.List;
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

    boolean hasCoverReference(
            UUID mediaAssetId
    );

    void lockCoverReferenceKey(
            UUID mediaAssetId
    );

    Optional<String> findMaxCoverMediaAssetId();

    List<String> findDistinctCoverMediaAssetIdsKeyset(
            WikiReferencedCoverKeysetQuery query
    );

    void flush();
}