package com.universe.community.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CommunityPostJpaEntity}.
 */
@Repository
public interface SpringDataCommunityPostJpaRepository extends JpaRepository<CommunityPostJpaEntity, String> {

    /**
     * Retrieves an existing post row by ID with an exclusive pessimistic write lock (SELECT ... FOR UPDATE).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.id = :id
            """)
    Optional<CommunityPostJpaEntity> findByIdForUpdate(@Param("id") String id);

    /**
     * Fetches the first page of newest Community posts ordered by {@code (created_at DESC, id DESC)}.
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<CommunityPostJpaEntity> findNewestPostsFirstPage(Pageable pageable);

    /**
     * Fetches subsequent page of newest Community posts strictly after the cursor point {@code (created_at, id)}.
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE (p.createdAt < :cursorCreatedAt)
               OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId)
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<CommunityPostJpaEntity> findNewestPostsAfterCursor(
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") String cursorId,
            Pageable pageable
    );

    /**
     * Fetches lightweight projection candidates for all live Community posts.
     */
    @Query("""
            SELECT p.id AS id, p.createdAt AS createdAt
            FROM CommunityPostJpaEntity p
            """)
    List<CommunityPostRankingCandidateProjection> findAllRankingCandidates();

    /**
     * Fetches Community posts by a collection of IDs for winner hydration.
     */
    List<CommunityPostJpaEntity> findByIdIn(java.util.Collection<String> ids);
}
