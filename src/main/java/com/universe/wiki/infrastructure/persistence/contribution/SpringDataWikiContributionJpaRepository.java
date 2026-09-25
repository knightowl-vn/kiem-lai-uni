package com.universe.wiki.infrastructure.persistence.contribution;

import org.springframework.data.domain.Page;
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

    @Query(value = """
            SELECT
                c.id AS id,
                c.status AS status,
                c.contributionType AS contributionType,
                c.contextType AS contextType,
                c.articleId AS articleId,
                c.articleTypeSnapshot AS articleTypeSnapshot,
                c.articleTitleSnapshot AS articleTitleSnapshot,
                c.articleSlugSnapshot AS articleSlugSnapshot,
                c.articleContentVersion AS articleContentVersion,
                c.submittedByUserId AS submittedByUserId,
                c.createdAt AS createdAt,
                (CASE WHEN c.contextType = 'TEXT_SELECTION' AND c.selectedText IS NOT NULL THEN true ELSE false END) AS hasSelectedText,
                SUBSTRING(c.message, 1, 200) AS messagePreview
            FROM WikiContributionJpaEntity c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:contributionType IS NULL OR c.contributionType = :contributionType)
              AND (:keyword IS NULL
                   OR LOWER(c.articleTitleSnapshot) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(c.articleSlugSnapshot) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """,
            countQuery = """
            SELECT COUNT(c) FROM WikiContributionJpaEntity c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:contributionType IS NULL OR c.contributionType = :contributionType)
              AND (:keyword IS NULL
                   OR LOWER(c.articleTitleSnapshot) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(c.articleSlugSnapshot) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<WikiContributionAdminInboxProjection> findAdminInboxPage(
            @Param("status") String status,
            @Param("contributionType") String contributionType,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("SELECT COUNT(c) FROM WikiContributionJpaEntity c WHERE c.status = :status")
    long countByStatus(@Param("status") String status);
}
