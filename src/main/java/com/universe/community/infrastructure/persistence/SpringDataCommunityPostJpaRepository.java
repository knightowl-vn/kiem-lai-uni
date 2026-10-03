package com.universe.community.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
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
     * Finds a single post by ID and canonical status.
     */
    Optional<CommunityPostJpaEntity> findByIdAndStatus(String id, String status);

    /**
     * Checks if a post exists by ID and canonical status.
     */
    boolean existsByIdAndStatus(String id, String status);

    /**
     * Fetches the first page of newest Community posts ordered by {@code (created_at DESC, id DESC)}.
     * Strictly limits results to {@code PUBLISHED} posts.
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.status = 'PUBLISHED'
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<CommunityPostJpaEntity> findNewestPostsFirstPage(Pageable pageable);

    /**
     * Fetches subsequent page of newest Community posts strictly after the cursor point {@code (created_at, id)}.
     * Strictly limits results to {@code PUBLISHED} posts.
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.status = 'PUBLISHED'
              AND ((p.createdAt < :cursorCreatedAt)
                OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<CommunityPostJpaEntity> findNewestPostsAfterCursor(
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") String cursorId,
            Pageable pageable
    );

    /**
     * Fetches the first page of Community posts authored by a specific user ordered by {@code (created_at DESC, id DESC)}.
     * Strictly limits results to {@code PUBLISHED} posts.
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.authorUserId = :authorUserId
              AND p.status = 'PUBLISHED'
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<CommunityPostJpaEntity> findAuthoredPostsFirstPage(
            @Param("authorUserId") String authorUserId,
            Pageable pageable
    );

    /**
     * Fetches subsequent page of Community posts authored by a specific user strictly after the cursor point {@code (created_at, id)}.
     * Strictly limits results to {@code PUBLISHED} posts.
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.authorUserId = :authorUserId
              AND p.status = 'PUBLISHED'
              AND ((p.createdAt < :cursorCreatedAt)
                OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<CommunityPostJpaEntity> findAuthoredPostsAfterCursor(
            @Param("authorUserId") String authorUserId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") String cursorId,
            Pageable pageable
    );

    /**
     * Fetches lightweight projection candidates for all live published Community posts.
     */
    @Query("""
            SELECT p.id AS id, p.createdAt AS createdAt
            FROM CommunityPostJpaEntity p
            WHERE p.status = 'PUBLISHED'
            """)
    List<CommunityPostRankingCandidateProjection> findAllRankingCandidates();

    /**
     * Fetches Community posts by a collection of IDs.
     */
    List<CommunityPostJpaEntity> findByIdIn(Collection<String> ids);

    /**
     * Fetches Community posts by a collection of IDs and canonical status.
     */
    List<CommunityPostJpaEntity> findByIdInAndStatus(Collection<String> ids, String status);
}
