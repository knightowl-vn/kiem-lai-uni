package com.universe.wiki.infrastructure.persistence.article;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpringDataWikiArticleAliasJpaRepository
        extends JpaRepository<WikiArticleAliasJpaEntity, String> {

    List<WikiArticleAliasJpaEntity> findAllByArticleId(String articleId);

    List<WikiArticleAliasJpaEntity> findAllByArticleIdOrderByCreatedAtAsc(String articleId);

    List<WikiArticleAliasJpaEntity> findByNormalizedAlias(String normalizedAlias);

    List<WikiArticleAliasJpaEntity> findByNormalizedAliasOrderByCreatedAtAsc(String normalizedAlias);

    boolean existsByArticleIdAndNormalizedAlias(String articleId, String normalizedAlias);

    void deleteAllByArticleId(String articleId);

    @Query("""
            SELECT a.articleId
            FROM WikiArticleAliasJpaEntity a
            JOIN WikiArticleJpaEntity article ON article.id = a.articleId
            WHERE a.normalizedAlias = :normalizedAlias
              AND article.status = 'PUBLISHED'
            ORDER BY article.updatedAt DESC, article.publishedAt DESC
            """)
    List<String> findPublishedArticleIdsByNormalizedAlias(@Param("normalizedAlias") String normalizedAlias);
}