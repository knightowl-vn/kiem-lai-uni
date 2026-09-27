package com.universe.wiki.infrastructure.persistence.contribution;

import java.time.Instant;

/**
 * Spring Data JPA projection for querying aggregated active contributor credits by article.
 */
public interface WikiArticlePublicContributorProjection {

    String getContributorUserId();

    long getActiveCreditCount();

    Instant getLastCreditedAt();
}
