package com.universe.wiki.infrastructure.persistence.article;

import java.time.Instant;

/**
 * Lightweight Spring Data projection for batch Wiki article listing without hydrating content.
 */
public interface WikiArticleListItemProjection {

    String getId();

    String getTitle();

    String getSlug();

    String getArticleType();

    String getStatus();

    String getSummary();

    String getUpdatedBy();

    Instant getCreatedAt();

    Instant getUpdatedAt();

    long getContentVersion();

    String getCoverMediaAssetId();

    Integer getCoverPositionX();

    Integer getCoverPositionY();
}
