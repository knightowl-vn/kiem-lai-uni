package com.universe.wiki.infrastructure.persistence.contribution;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA Repository cho bảng wiki_contributions.
 */
@Repository
public interface SpringDataWikiContributionJpaRepository
        extends JpaRepository<WikiContributionJpaEntity, String> {

    @Query("SELECT c FROM WikiContributionJpaEntity c " +
           "WHERE c.submittedByUserId = :userId " +
           "AND c.articleId = :articleId " +
           "AND c.createdAt >= :cutoff " +
           "ORDER BY c.createdAt DESC")
    List<WikiContributionJpaEntity> findRecentByUserIdAndArticleId(
            @Param("userId") String userId,
            @Param("articleId") String articleId,
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );
}
