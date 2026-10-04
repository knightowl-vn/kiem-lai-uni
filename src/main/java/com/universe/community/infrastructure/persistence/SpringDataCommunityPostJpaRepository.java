package com.universe.community.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
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
     * Fetches the first page of newest Community posts ordered by {@code (published_at DESC, id DESC)}.
     * Strictly limits results to {@code PUBLISHED} posts.
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.status = 'PUBLISHED'
            ORDER BY p.publishedAt DESC, p.id DESC
            """)
    List<CommunityPostJpaEntity> findNewestPostsFirstPage(Pageable pageable);

    /**
     * Fetches subsequent page of newest Community posts strictly after the cursor point {@code (published_at, id)}.
     * Strictly limits results to {@code PUBLISHED} posts.
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.status = 'PUBLISHED'
              AND ((p.publishedAt < :cursorPublishedAt)
                OR (p.publishedAt = :cursorPublishedAt AND p.id < :cursorId))
            ORDER BY p.publishedAt DESC, p.id DESC
            """)
    List<CommunityPostJpaEntity> findNewestPostsAfterCursor(
            @Param("cursorPublishedAt") Instant cursorPublishedAt,
            @Param("cursorId") String cursorId,
            Pageable pageable
    );

    /**
     * Fetches the first page of Community posts authored by a specific user ordered by {@code (published_at DESC, id DESC)}.
     * Strictly limits results to {@code PUBLISHED} posts.
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.authorUserId = :authorUserId
              AND p.status = 'PUBLISHED'
            ORDER BY p.publishedAt DESC, p.id DESC
            """)
    List<CommunityPostJpaEntity> findAuthoredPostsFirstPage(
            @Param("authorUserId") String authorUserId,
            Pageable pageable
    );

    /**
     * Fetches subsequent page of Community posts authored by a specific user strictly after the cursor point {@code (published_at, id)}.
     * Strictly limits results to {@code PUBLISHED} posts.
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.authorUserId = :authorUserId
              AND p.status = 'PUBLISHED'
              AND ((p.publishedAt < :cursorPublishedAt)
                OR (p.publishedAt = :cursorPublishedAt AND p.id < :cursorId))
            ORDER BY p.publishedAt DESC, p.id DESC
            """)
    List<CommunityPostJpaEntity> findAuthoredPostsAfterCursor(
            @Param("authorUserId") String authorUserId,
            @Param("cursorPublishedAt") Instant cursorPublishedAt,
            @Param("cursorId") String cursorId,
            Pageable pageable
    );

    /**
     * Fetches lightweight projection candidates for all live published Community posts.
     */
    @Query("""
            SELECT p.id AS id, p.publishedAt AS publishedAt
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

    /**
     * Fetches a page of Community posts in review queue: either PENDING_REVIEW or PUBLISHED with pending_caption.
     * Ordered oldest request first (review_requested_at ASC, id ASC).
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.reviewRequestedAt IS NOT NULL
              AND ((p.status = 'PENDING_REVIEW')
                OR (p.status = 'PUBLISHED' AND p.pendingCaption IS NOT NULL))
            ORDER BY p.reviewRequestedAt ASC, p.id ASC
            """)
    Page<CommunityPostJpaEntity> findPendingReviewPosts(Pageable pageable);

    /**
     * Fetches Community posts in review queue authored by a specific user:
     * either PENDING_REVIEW or PUBLISHED with pending_caption.
     * Ordered newest review request first (review_requested_at DESC, id DESC).
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.authorUserId = :authorUserId
              AND p.reviewRequestedAt IS NOT NULL
              AND ((p.status = 'PENDING_REVIEW')
                OR (p.status = 'PUBLISHED' AND p.pendingCaption IS NOT NULL))
            ORDER BY p.reviewRequestedAt DESC, p.id DESC
            """)
    List<CommunityPostJpaEntity> findPendingReviewPostsByAuthor(@Param("authorUserId") String authorUserId);

    /**
     * Fetches a page of Community posts with HIDDEN status ordered newest first (updated_at DESC, id DESC).
     */
    @Query("""
            SELECT p FROM CommunityPostJpaEntity p
            WHERE p.status = 'HIDDEN'
            ORDER BY p.updatedAt DESC, p.id DESC
            """)
    Page<CommunityPostJpaEntity> findHiddenPosts(Pageable pageable);
}
