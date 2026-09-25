package com.universe.wiki.infrastructure.persistence.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA Repository cho entity WikiContributionCreditJpaEntity.
 */
@Repository
public interface WikiContributionCreditSpringDataRepository extends JpaRepository<WikiContributionCreditJpaEntity, String> {

    Optional<WikiContributionCreditJpaEntity> findByContributionId(String contributionId);
}
