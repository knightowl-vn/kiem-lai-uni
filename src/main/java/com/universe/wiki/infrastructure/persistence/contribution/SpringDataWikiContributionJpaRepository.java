package com.universe.wiki.infrastructure.persistence.contribution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository cho bảng wiki_contributions.
 */
@Repository
public interface SpringDataWikiContributionJpaRepository
        extends JpaRepository<WikiContributionJpaEntity, String> {
}
