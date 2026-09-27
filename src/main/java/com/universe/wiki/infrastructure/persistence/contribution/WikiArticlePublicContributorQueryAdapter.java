package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.wiki.application.article.query.contributor.WikiArticlePublicContributorAggregate;
import com.universe.wiki.application.ports.WikiArticlePublicContributorQueryPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence adapter implementing WikiArticlePublicContributorQueryPort using Spring Data JPA.
 *
 * <p>Enforces a hard limit of 51 distinct candidate contributors database-side to support
 * truncation detection, grouped by contributor, filtering out non-active credits, and ordered
 * by latest active creditedAt DESC with contributorUserId ASC tie-breaker.
 */
@Component
public class WikiArticlePublicContributorQueryAdapter implements WikiArticlePublicContributorQueryPort {

    private static final int CANDIDATE_HARD_LIMIT = 51;
    private final SpringDataWikiContributionJpaRepository contributionRepository;

    public WikiArticlePublicContributorQueryAdapter(SpringDataWikiContributionJpaRepository contributionRepository) {
        this.contributionRepository = Objects.requireNonNull(contributionRepository, "contributionRepository cannot be null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<WikiArticlePublicContributorAggregate> findActiveContributorsByArticleId(UUID articleId) {
        if (articleId == null) {
            return List.of();
        }

        List<WikiArticlePublicContributorProjection> projections =
                contributionRepository.findActiveContributorsByArticleId(
                        articleId.toString(),
                        PageRequest.of(0, CANDIDATE_HARD_LIMIT)
                );

        if (projections == null || projections.isEmpty()) {
            return List.of();
        }

        return projections.stream()
                .filter(p -> p != null && p.getContributorUserId() != null && p.getActiveCreditCount() > 0 && p.getLastCreditedAt() != null)
                .map(p -> new WikiArticlePublicContributorAggregate(
                        UUID.fromString(p.getContributorUserId()),
                        p.getActiveCreditCount(),
                        p.getLastCreditedAt()
                ))
                .toList();
    }
}
