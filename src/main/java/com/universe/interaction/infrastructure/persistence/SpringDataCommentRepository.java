package com.universe.interaction.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
