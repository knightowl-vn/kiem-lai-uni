package com.universe.community.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link CommunityPostRevisionJpaEntity}.
 */
@Repository
public interface SpringDataCommunityPostRevisionJpaRepository extends JpaRepository<CommunityPostRevisionJpaEntity, String> {

    /**
     * Finds all revisions for a given post ID ordered oldest first (revision_number ASC).
     */
    @Query("""
            SELECT r FROM CommunityPostRevisionJpaEntity r
            WHERE r.postId = :postId
            ORDER BY r.revisionNumber ASC
            """)
    List<CommunityPostRevisionJpaEntity> findByPostIdOrderByRevisionNumberAsc(@Param("postId") String postId);
}
