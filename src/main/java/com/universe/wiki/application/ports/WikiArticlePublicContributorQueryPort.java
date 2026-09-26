package com.universe.wiki.application.ports;

import com.universe.wiki.application.article.query.contributor.WikiArticlePublicContributorAggregate;

import java.util.List;
import java.util.UUID;

/**
 * Port for querying public active contributor credit aggregates for a published Wiki article.
 */
public interface WikiArticlePublicContributorQueryPort {

    /**
     * Retrieves the active credited contributor aggregates for the specified article ID.
     *
     * <p>Enforces a bounded candidate query (up to 51 aggregates) grouped by contributor,
     * considering active credits only, and ordered by latest active creditedAt DESC,
     * with contributorUserId ASC as a deterministic tie-breaker.
     *
     * @param articleId the unique identifier of the Wiki article
     * @return list of active contributor aggregates, empty if none exist
     */
    List<WikiArticlePublicContributorAggregate> findActiveContributorsByArticleId(UUID articleId);
}
