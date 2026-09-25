package com.universe.wiki.infrastructure.persistence.contribution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataWikiContributionWorkflowEventJpaRepository
        extends JpaRepository<WikiContributionWorkflowEventJpaEntity, String> {

    List<WikiContributionWorkflowEventJpaEntity>
            findByContributionIdOrderByCreatedAtAscIdAsc(String contributionId);
}
