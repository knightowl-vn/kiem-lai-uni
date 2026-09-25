package com.universe.wiki.application.article.query.contributor;

import java.util.Objects;
import java.util.UUID;

/**
 * Minimal query aggregate projection for article-local public contributors.
 *
 * <p>Contains only the contributor's technical ID for subsequent Identity contract bulk lookup
 * and the aggregated active credit count for the specified article.
 */
public record WikiArticlePublicContributorAggregate(
        UUID contributorUserId,
        long activeCreditCount
) {
    public WikiArticlePublicContributorAggregate {
        Objects.requireNonNull(contributorUserId, "contributorUserId cannot be null");
        if (activeCreditCount < 1) {
            throw new IllegalArgumentException("activeCreditCount must be at least 1");
        }
    }
}
