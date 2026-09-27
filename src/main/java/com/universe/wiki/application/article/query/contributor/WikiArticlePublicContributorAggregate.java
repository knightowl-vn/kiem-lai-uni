package com.universe.wiki.application.article.query.contributor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Minimal query aggregate projection for article-local public contributors.
 *
 * <p>Contains only the contributor's technical ID for subsequent Identity contract bulk lookup,
 * the aggregated active credit count for the specified article, and the timestamp of the latest active credit.
 */
public record WikiArticlePublicContributorAggregate(
        UUID contributorUserId,
        long activeCreditCount,
        Instant lastCreditedAt
) {
    public WikiArticlePublicContributorAggregate {
        Objects.requireNonNull(contributorUserId, "contributorUserId cannot be null");
        Objects.requireNonNull(lastCreditedAt, "lastCreditedAt cannot be null");
        if (activeCreditCount < 1) {
            throw new IllegalArgumentException("activeCreditCount must be at least 1");
        }
    }
}
