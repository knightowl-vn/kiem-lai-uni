package com.universe.interaction.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link InteractionReportJpaEntity}.
 */
@Repository
public interface SpringDataInteractionReportRepository extends JpaRepository<InteractionReportJpaEntity, String> {

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
     * Retrieves a page of report queue rows sorted deterministically newest first (created_at DESC, id DESC).
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
                        c.author_user_id AS commentAuthorUserId,
                        c.target_type AS targetType,
                        c.target_id AS targetId,
                        c.status AS commentStatus
                    FROM interaction_reports r
                    INNER JOIN interaction_comments c ON c.id = r.comment_id
                    WHERE (:status IS NULL OR r.status = :status)
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    ORDER BY r.created_at DESC, r.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM interaction_reports r
                    INNER JOIN interaction_comments c ON c.id = r.comment_id
                    WHERE (:status IS NULL OR r.status = :status)
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    """,
            nativeQuery = true
    )
    Page<InteractionReportQueueRowProjection> findQueueNewest(
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("targetType") String targetType,
            Pageable pageable
    );

    /**
     * Retrieves a page of report queue rows sorted deterministically oldest first (created_at ASC, id ASC).
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
                        c.author_user_id AS commentAuthorUserId,
                        c.target_type AS targetType,
                        c.target_id AS targetId,
                        c.status AS commentStatus
                    FROM interaction_reports r
                    INNER JOIN interaction_comments c ON c.id = r.comment_id
                    WHERE (:status IS NULL OR r.status = :status)
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    ORDER BY r.created_at ASC, r.id ASC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM interaction_reports r
                    INNER JOIN interaction_comments c ON c.id = r.comment_id
                    WHERE (:status IS NULL OR r.status = :status)
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    """,
            nativeQuery = true
    )
    Page<InteractionReportQueueRowProjection> findQueueOldest(
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("targetType") String targetType,
            Pageable pageable
    );
}
