package com.universe.wiki.infrastructure.persistence.article;

import java.time.Instant;

/**
 * Spring Data Projection cho tra cứu ứng viên alias Wiki.
 */
public interface WikiArticleAliasSearchMatchProjection {

    String getArticleId();

    String getTitle();

    String getSlug();

    String getArticleType();

    String getAlias();

    String getSummary();

    Instant getUpdatedAt();

    String getCoverMediaAssetId();

    Integer getCoverPositionX();

    Integer getCoverPositionY();
}
