package com.universe.interaction.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link InteractionReportJpaEntity}.
 */
@Repository
public interface SpringDataInteractionReportRepository extends JpaRepository<InteractionReportJpaEntity, String> {

    /**
     * Retrieves an existing report row by ID with an exclusive pessimistic write lock (SELECT ... FOR UPDATE).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM InteractionReportJpaEntity r
            WHERE r.id = :id
            """)
    Optional<InteractionReportJpaEntity> findByIdForUpdate(@Param("id") String id);

    /**
     * Checks if a report exists for a specific comment, reporter, and status.
     *
     * @param commentId the target comment ID string
     * @param reporterUserId the reporter user ID string
     * @param status the status string (e.g. "PENDING")
     * @return true if a matching report exists
     */
    boolean existsByCommentIdAndReporterUserIdAndStatus(
            String commentId,
            String reporterUserId,
            String status
    );

    /**
     * Retrieves a page of pending report queue rows sorted deterministically newest first (created_at DESC, id DESC).
     */
    @Query(
            value = """
                    SELECT
                        r.id AS reportId,
                        r.comment_id AS commentId,
                        r.reporter_user_id AS reporterUserId,
                        r.reason AS reason,
                        r.description AS description,
                        r.reported_body_snapshot AS reportedBodySnapshot,
                        r.status AS reportStatus,
                        r.created_at AS createdAt,
                        r.resolved_by_user_id AS resolvedByUserId,
                        r.resolved_at AS resolvedAt,
                        r.moderation_action AS moderationAction,
                        c.author_user_id AS commentAuthorUserId,
                        c.target_type AS targetType,
                        c.target_id AS targetId,
                        c.status AS commentStatus
                    FROM interaction_reports r
                    INNER JOIN interaction_comments c ON c.id = r.comment_id
                    WHERE r.status = 'PENDING'
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    ORDER BY r.created_at DESC, r.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM interaction_reports r
                    INNER JOIN interaction_comments c ON c.id = r.comment_id
                    WHERE r.status = 'PENDING'
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    """,
            nativeQuery = true
    )
    Page<InteractionReportQueueRowProjection> findPendingQueueNewest(
            @Param("reason") String reason,
            @Param("targetType") String targetType,
            Pageable pageable
    );

    /**
     * Retrieves a page of pending report queue rows sorted deterministically oldest first (created_at ASC, id ASC).
     */
    @Query(
            value = """
                    SELECT
                        r.id AS reportId,
                        r.comment_id AS commentId,
                        r.reporter_user_id AS reporterUserId,
                        r.reason AS reason,
                        r.description AS description,
                        r.reported_body_snapshot AS reportedBodySnapshot,
                        r.status AS reportStatus,
                        r.created_at AS createdAt,
                        r.resolved_by_user_id AS resolvedByUserId,
                        r.resolved_at AS resolvedAt,
                        r.moderation_action AS moderationAction,
                        c.author_user_id AS commentAuthorUserId,
                        c.target_type AS targetType,
                        c.target_id AS targetId,
                        c.status AS commentStatus
                    FROM interaction_reports r
                    INNER JOIN interaction_comments c ON c.id = r.comment_id
                    WHERE r.status = 'PENDING'
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    ORDER BY r.created_at ASC, r.id ASC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM interaction_reports r
                    INNER JOIN interaction_comments c ON c.id = r.comment_id
                    WHERE r.status = 'PENDING'
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    """,
            nativeQuery = true
    )
    Page<InteractionReportQueueRowProjection> findPendingQueueOldest(
            @Param("reason") String reason,
            @Param("targetType") String targetType,
            Pageable pageable
    );

    /**
     * Retrieves a page of processed report history sorted deterministically by resolution time (resolved_at DESC, id DESC).
     */
    @Query(
            value = """
                    SELECT
                        r.id AS reportId,
                        r.comment_id AS commentId,
                        r.reporter_user_id AS reporterUserId,
                        r.reason AS reason,
                        r.description AS description,
                        r.reported_body_snapshot AS reportedBodySnapshot,
                        r.status AS reportStatus,
                        r.created_at AS createdAt,
                        r.resolved_by_user_id AS resolvedByUserId,
                        r.resolved_at AS resolvedAt,
                        r.moderation_action AS moderationAction,
                        c.author_user_id AS commentAuthorUserId,
                        c.target_type AS targetType,
                        c.target_id AS targetId,
                        c.status AS commentStatus
                    FROM interaction_reports r
                    INNER JOIN interaction_comments c ON c.id = r.comment_id
                    WHERE r.status IN ('RESOLVED_ACTION_TAKEN', 'RESOLVED_NO_ACTION')
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    ORDER BY r.resolved_at DESC, r.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM interaction_reports r
                    INNER JOIN interaction_comments c ON c.id = r.comment_id
                    WHERE r.status IN ('RESOLVED_ACTION_TAKEN', 'RESOLVED_NO_ACTION')
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    """,
            nativeQuery = true
    )
    Page<InteractionReportQueueRowProjection> findProcessedQueue(
            @Param("reason") String reason,
            @Param("targetType") String targetType,
            Pageable pageable
    );
}
