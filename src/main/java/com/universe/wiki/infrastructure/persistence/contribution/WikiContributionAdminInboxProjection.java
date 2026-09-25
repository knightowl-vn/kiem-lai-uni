package com.universe.wiki.infrastructure.persistence.contribution;

import java.time.Instant;

/**
 * Lightweight Spring Data JPA projection for the admin Wiki contribution triage queue.
 *
 * <p>Avoids hydrating the full entity, large message bodies (TEXT), and full selection evidence.
 * Infrastructure-only projection; never leaks beyond the persistence adapter.
 */
public interface WikiContributionAdminInboxProjection {

    String getId();

    String getStatus();

    String getContributionType();

    String getContextType();

    String getArticleId();

    String getArticleTypeSnapshot();

    String getArticleTitleSnapshot();

    String getArticleSlugSnapshot();

    long getArticleContentVersion();

    String getSubmittedByUserId();

    Instant getCreatedAt();

    boolean getHasSelectedText();

    String getMessagePreview();
}
