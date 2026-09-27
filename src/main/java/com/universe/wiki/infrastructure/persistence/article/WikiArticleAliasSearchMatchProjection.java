package com.universe.wiki.infrastructure.persistence.article;

/**
 * Spring Data Projection cho tra cứu ứng viên alias Wiki.
 */
public interface WikiArticleAliasSearchMatchProjection {

    String getArticleId();

    String getTitle();

    String getSlug();

    String getArticleType();

    String getAlias();
}
