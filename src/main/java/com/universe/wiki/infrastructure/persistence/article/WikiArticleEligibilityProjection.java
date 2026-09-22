package com.universe.wiki.infrastructure.persistence.article;

/**
 * Spring Data JPA Projection chỉ truy vấn id, article_type, và status của WikiArticle.
 */
public interface WikiArticleEligibilityProjection {

    String getId();

    String getArticleType();

    String getStatus();
}
