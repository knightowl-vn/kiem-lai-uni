package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.infrastructure.persistence.reaction.ReactionJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link CommentJpaEntity}.
 *
 * <p>Supports:
 * <ul>
 *   <li>Pessimistic row locking for concurrency-safe mutation orchestration;</li>
 *   <li>Zero-based slice pagination for active root comments, avoiding COUNT(*) queries;</li>
 *   <li>Flat retrieval of thread replies ordered chronologically.</li>
 * </ul>
 */
@Repository
public interface SpringDataCommentRepository extends JpaRepository<CommentJpaEntity, String> {

    /**
     * Retrieves an existing comment row by ID with an exclusive pessimistic write lock (SELECT ... FOR UPDATE).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM CommentJpaEntity c
            WHERE c.id = :id
            """)
    Optional<CommentJpaEntity> findByIdForUpdate(@Param("id") String id);

    /**
     * Retrieves all comments for a target with an exclusive pessimistic write lock (SELECT ... FOR UPDATE),
     * forcing the index scan through {@code idx_interaction_comments_target_id} to guarantee that physical
     * InnoDB row locks are acquired strictly in canonical {@code id ASC} order.
     */
    @Query(value = """
            SELECT * FROM interaction_comments FORCE INDEX (idx_interaction_comments_target_id)
            WHERE target_type = :targetType
              AND target_id = :targetId
            ORDER BY id ASC
            FOR UPDATE
            """, nativeQuery = true)
    List<CommentJpaEntity> findAllByTargetForUpdate(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId
    );

    /**
     * Retrieves a pageable slice of active root comments for a given target.
     *
     * <p>Roots are characterized by {@code parent_comment_id IS NULL}.
     * Deterministic ordering by {@code created_at DESC, id DESC} uses the composite index
     * {@code idx_interaction_comments_target_parent_created_id}.
     */
    @Query("""
            SELECT c FROM CommentJpaEntity c
            WHERE c.targetType = :targetType
              AND c.targetId = :targetId
              AND c.parentCommentId IS NULL
              AND c.status = 'ACTIVE'
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    Slice<CommentJpaEntity> findActiveRoots(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            Pageable pageable
    );

    /**
     * Retrieves a pageable slice of active root comments for a given target ordered by FEATURED engagement.
     *
     * <p>Roots are characterized by {@code parent_comment_id IS NULL} and {@code status = 'ACTIVE'}.
     * Global ranking formula:
     * {@code engagementScore = (active root reaction count) + (active visible reply count)}.
     * Deterministic ordering by {@code engagementScore DESC, createdAt DESC, id DESC}.
     */
    @Query("""
            SELECT c FROM CommentJpaEntity c
            WHERE c.targetType = :targetType
              AND c.targetId = :targetId
              AND c.parentCommentId IS NULL
              AND c.status = 'ACTIVE'
            ORDER BY (
                (SELECT COUNT(r) FROM ReactionJpaEntity r WHERE r.targetType = 'COMMENT' AND r.targetId = c.id)
                +
                (SELECT COUNT(rep) FROM CommentJpaEntity rep WHERE rep.threadRootCommentId = c.id AND rep.status = 'ACTIVE')
            ) DESC, c.createdAt DESC, c.id DESC
            """)
    Slice<CommentJpaEntity> findFeaturedRoots(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            Pageable pageable
    );

    /**
     * Retrieves all replies belonging to a given thread root.
     *
     * <p>Includes both ACTIVE and DELETED replies.
     * Deterministic ordering by {@code created_at ASC, id ASC} uses the composite index
     * {@code idx_interaction_comments_thread_root_created_id}.
     */
    @Query("""
            SELECT c FROM CommentJpaEntity c
            WHERE c.threadRootCommentId = :threadRootCommentId
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<CommentJpaEntity> findThreadReplies(
            @Param("threadRootCommentId") String threadRootCommentId
    );

    /**
     * Retrieves the IDs of all active root comments for a given target.
     *
     * <p>Roots are characterized by {@code parent_comment_id IS NULL} and {@code status = 'ACTIVE'}.
     * Returns only scalar IDs to avoid entity hydration overhead.
     */
    @Query("""
            SELECT c.id FROM CommentJpaEntity c
            WHERE c.targetType = :targetType
              AND c.targetId = :targetId
              AND c.parentCommentId IS NULL
              AND c.status = 'ACTIVE'
            """)
    List<String> findActiveRootCommentIds(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId
    );

    /**
     * Retrieves active root comments matching the specified IDs for a given target.
     *
     * <p>Roots are characterized by {@code parent_comment_id IS NULL} and {@code status = 'ACTIVE'}.
     * Deterministic ordering by {@code created_at DESC, id DESC}.
     */
    @Query("""
            SELECT c FROM CommentJpaEntity c
            WHERE c.id IN :ids
              AND c.targetType = :targetType
              AND c.targetId = :targetId
              AND c.parentCommentId IS NULL
              AND c.status = 'ACTIVE'
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    List<CommentJpaEntity> findActiveRootsByIds(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("ids") Collection<String> ids
    );

    /**
     * Retrieves all replies belonging to a collection of thread roots.
     *
     * <p>Includes both ACTIVE and DELETED replies.
     * Deterministic ordering by {@code created_at ASC, id ASC}.
     */
    @Query("""
            SELECT c FROM CommentJpaEntity c
            WHERE c.threadRootCommentId IN :threadRootCommentIds
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<CommentJpaEntity> findThreadRepliesByRootIds(
            @Param("threadRootCommentIds") Collection<String> threadRootCommentIds
    );

    /**
     * Counts visible active replies grouped by thread root ID.
     *
     * <p>Excludes tombstones (DELETED replies) and non-reply records.
     * Returns pairs of [threadRootCommentId, count].
     */
    @Query("""
            SELECT c.threadRootCommentId, COUNT(c)
            FROM CommentJpaEntity c
            WHERE c.threadRootCommentId IN :threadRootCommentIds
              AND c.status = 'ACTIVE'
            GROUP BY c.threadRootCommentId
            """)
    List<Object[]> countActiveRepliesByThreadRootIds(
            @Param("threadRootCommentIds") Collection<String> threadRootCommentIds
    );

    /**
     * Aggregates target discussion metrics in a single query:
     * - threadCount: COUNT(DISTINCT r.id) of active roots for the target.
     * - activeRepliesCount: COUNT(DISTINCT c.id) of active replies under active roots for the target.
     *
     * <p>Returns exactly one row: [threadCount, activeRepliesCount].
     */
    @Query("""
            SELECT
                COUNT(DISTINCT r.id),
                COUNT(DISTINCT c.id)
            FROM CommentJpaEntity r
            LEFT JOIN CommentJpaEntity c
                ON c.threadRootCommentId = r.id
                AND c.targetType = :targetType
                AND c.targetId = :targetId
                AND c.status = 'ACTIVE'
            WHERE r.targetType = :targetType
              AND r.targetId = :targetId
              AND r.parentCommentId IS NULL
              AND r.status = 'ACTIVE'
            """)
    List<Object[]> countTargetMetrics(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId
    );

    /**
     * Retrieves a paginated page of active comments authored by a specific user scoped by target types.
     *
     * <p>Filters out DELETED comments entirely (status = 'ACTIVE').
     * Deterministic ordering by {@code created_at DESC, id DESC} uses the author index
     * {@code idx_interaction_comments_author_status_created_id}.
     */
    @Query("""
            SELECT c FROM CommentJpaEntity c
            WHERE c.authorUserId = :authorUserId
              AND c.status = 'ACTIVE'
              AND c.targetType IN (:targetTypes)
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    Page<CommentJpaEntity> findAuthoredComments(
            @Param("authorUserId") String authorUserId,
            @Param("targetTypes") Collection<String> targetTypes,
            Pageable pageable
    );

    /**
     * Bulk physically deletes comments matching the specified IDs.
     */
    @Modifying
    @Query("DELETE FROM CommentJpaEntity c WHERE c.id IN :ids")
    void deleteAllByIds(@Param("ids") Collection<String> ids);

    /**
     * Retrieves ALL comment IDs for a given target regardless of status or hierarchy.
     */
    @Query("""
            SELECT c.id FROM CommentJpaEntity c
            WHERE c.targetType = :targetType
              AND c.targetId = :targetId
            """)
    List<String> findAllCommentIdsByTarget(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId
    );

    /**
     * Counts active comments and replies grouped by targetId for multiple targets.
     */
    @Query("""
            SELECT c.targetId, COUNT(c)
            FROM CommentJpaEntity c
            WHERE c.targetType = :targetType
              AND c.targetId IN :targetIds
              AND c.status = 'ACTIVE'
            GROUP BY c.targetId
            """)
    List<Object[]> countActiveCommentsByTargetIds(
            @Param("targetType") String targetType,
            @Param("targetIds") Collection<String> targetIds
    );

    /**
     * Checks whether any comments exist that have the specified comment as their direct parent.
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN TRUE ELSE FALSE END FROM CommentJpaEntity c WHERE c.parentCommentId = :parentCommentId")
    boolean existsByParentCommentId(@Param("parentCommentId") String parentCommentId);

    /**
     * Checks whether any comments exist that belong to the specified thread root.
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN TRUE ELSE FALSE END FROM CommentJpaEntity c WHERE c.threadRootCommentId = :threadRootCommentId")
    boolean existsByThreadRootCommentId(@Param("threadRootCommentId") String threadRootCommentId);
}
