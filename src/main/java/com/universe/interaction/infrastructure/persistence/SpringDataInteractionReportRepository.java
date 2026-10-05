package com.universe.interaction.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
     * Retrieves the scalar target type and target ID for a report without loading the entity into the persistence context.
     */
     @Query("""
             SELECT r.targetType, r.targetId FROM InteractionReportJpaEntity r
             WHERE r.id = :id
             """)
     List<Object[]> findTargetMetadataById(@Param("id") String id);

    /**
     * Retrieves the scalar target ID for a report without loading the entity into the persistence context.
     */
    @Query("""
            SELECT r.targetId FROM InteractionReportJpaEntity r
            WHERE r.id = :id
            """)
    Optional<String> findTargetIdById(@Param("id") String id);

    /**
     * Checks if a report exists for a specific target type, target ID, reporter, and status.
     */
    boolean existsByTargetTypeAndTargetIdAndReporterUserIdAndStatus(
            String targetType,
            String targetId,
            String reporterUserId,
            String status
    );

    /**
     * Checks if a report exists for a specific target type, target ID, and status.
     */
    boolean existsByTargetTypeAndTargetIdAndStatus(
            String targetType,
            String targetId,
            String status
    );

    /**
     * Retrieves a page of pending report queue rows sorted deterministically newest first (created_at DESC, id DESC).
     */
    @Query(
            value = """
                    SELECT
                        r.id AS reportId,
                        r.target_type AS reportTargetType,
                        r.target_id AS reportTargetId,
                        r.reporter_user_id AS reporterUserId,
                        r.reason AS reason,
                        r.description AS description,
                        r.content_snapshot AS reportedContentSnapshot,
                        r.status AS reportStatus,
                        r.created_at AS createdAt,
                        r.resolved_by_user_id AS resolvedByUserId,
                        r.resolved_at AS resolvedAt,
                        r.moderation_action AS moderationAction,
                        r.target_deleted_at AS targetDeletedAt,
                        c.author_user_id AS commentAuthorUserId,
                        c.target_type AS contentTargetType,
                        c.target_id AS contentTargetId,
                        c.status AS commentStatus
                    FROM interaction_reports r
                    LEFT JOIN interaction_comments c ON c.id = r.target_id AND r.target_type = 'COMMENT'
                    WHERE r.target_type = 'COMMENT'
                      AND r.status = 'PENDING'
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    ORDER BY r.created_at DESC, r.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM interaction_reports r
                    LEFT JOIN interaction_comments c ON c.id = r.target_id AND r.target_type = 'COMMENT'
                    WHERE r.target_type = 'COMMENT'
                      AND r.status = 'PENDING'
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
                        r.target_type AS reportTargetType,
                        r.target_id AS reportTargetId,
                        r.reporter_user_id AS reporterUserId,
                        r.reason AS reason,
                        r.description AS description,
                        r.content_snapshot AS reportedContentSnapshot,
                        r.status AS reportStatus,
                        r.created_at AS createdAt,
                        r.resolved_by_user_id AS resolvedByUserId,
                        r.resolved_at AS resolvedAt,
                        r.moderation_action AS moderationAction,
                        r.target_deleted_at AS targetDeletedAt,
                        c.author_user_id AS commentAuthorUserId,
                        c.target_type AS contentTargetType,
                        c.target_id AS contentTargetId,
                        c.status AS commentStatus
                    FROM interaction_reports r
                    LEFT JOIN interaction_comments c ON c.id = r.target_id AND r.target_type = 'COMMENT'
                    WHERE r.target_type = 'COMMENT'
                      AND r.status = 'PENDING'
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    ORDER BY r.created_at ASC, r.id ASC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM interaction_reports r
                    LEFT JOIN interaction_comments c ON c.id = r.target_id AND r.target_type = 'COMMENT'
                    WHERE r.target_type = 'COMMENT'
                      AND r.status = 'PENDING'
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
                        r.target_type AS reportTargetType,
                        r.target_id AS reportTargetId,
                        r.reporter_user_id AS reporterUserId,
                        r.reason AS reason,
                        r.description AS description,
                        r.content_snapshot AS reportedContentSnapshot,
                        r.status AS reportStatus,
                        r.created_at AS createdAt,
                        r.resolved_by_user_id AS resolvedByUserId,
                        r.resolved_at AS resolvedAt,
                        r.moderation_action AS moderationAction,
                        r.target_deleted_at AS targetDeletedAt,
                        c.author_user_id AS commentAuthorUserId,
                        c.target_type AS contentTargetType,
                        c.target_id AS contentTargetId,
                        c.status AS commentStatus
                    FROM interaction_reports r
                    LEFT JOIN interaction_comments c ON c.id = r.target_id AND r.target_type = 'COMMENT'
                    WHERE r.target_type = 'COMMENT'
                      AND r.status IN ('RESOLVED_ACTION_TAKEN', 'RESOLVED_NO_ACTION')
                      AND (:reason IS NULL OR r.reason = :reason)
                      AND (:targetType IS NULL OR c.target_type = :targetType)
                    ORDER BY r.resolved_at DESC, r.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM interaction_reports r
                    LEFT JOIN interaction_comments c ON c.id = r.target_id AND r.target_type = 'COMMENT'
                    WHERE r.target_type = 'COMMENT'
                      AND r.status IN ('RESOLVED_ACTION_TAKEN', 'RESOLVED_NO_ACTION')
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

    /**
     * Selects up to {@code limit} expired report IDs according to hierarchical retention rules:
     * 1. If target_deleted_at IS NOT NULL: eligible if target_deleted_at < cutoff
     * 2. Else if terminal status (RESOLVED_ACTION_TAKEN, RESOLVED_NO_ACTION): eligible if resolved_at < cutoff
     * 3. Else (PENDING with non-deleted target): never eligible.
     */
    @Query(
            value = """
                    SELECT r.id
                    FROM interaction_reports r
                    WHERE (
                        (r.target_deleted_at IS NOT NULL AND r.target_deleted_at < :cutoff)
                        OR
                        (r.target_deleted_at IS NULL AND r.status IN ('RESOLVED_ACTION_TAKEN', 'RESOLVED_NO_ACTION') AND r.resolved_at < :cutoff)
                    )
                    ORDER BY COALESCE(r.target_deleted_at, r.resolved_at) ASC, r.id ASC
                    """,
            nativeQuery = true
    )
    List<String> findExpiredReportIds(
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

    /**
     * Stamps target_deleted_at on all reports for the given target IDs where target_deleted_at is currently NULL.
     */
    @Modifying
    @Query("""
            UPDATE InteractionReportJpaEntity r
            SET r.targetDeletedAt = :deletedAt
            WHERE r.targetType = :targetType
              AND r.targetId IN :targetIds
              AND r.targetDeletedAt IS NULL
            """)
    int stampTargetDeletedAt(
            @Param("targetType") String targetType,
            @Param("targetIds") Collection<String> targetIds,
            @Param("deletedAt") Instant deletedAt
    );

    /**
     * Deletes interaction reports matching the given IDs.
     */
    @Modifying
    @Query(
            value = """
                    DELETE FROM interaction_reports
                    WHERE id IN (:ids)
                    """,
            nativeQuery = true
    )
    int deleteByIdIn(@Param("ids") List<String> ids);

    /**
     * Counts reports for the specified target type and IDs where target_deleted_at IS NULL.
     */
    @Query("""
            SELECT COUNT(r) FROM InteractionReportJpaEntity r
            WHERE r.targetType = :targetType
              AND r.targetId IN :targetIds
              AND r.targetDeletedAt IS NULL
            """)
    long countUnstampedReportsByTargets(
            @Param("targetType") String targetType,
            @Param("targetIds") Collection<String> targetIds
    );

    /**
     * Retrieves a page of community post reports sorted newest first (created_at DESC, id DESC).
     */
    @Query("""
            SELECT r FROM InteractionReportJpaEntity r
            WHERE r.targetType = 'COMMUNITY_POST'
              AND (:status IS NULL OR r.status = :status)
              AND (:reason IS NULL OR r.reason = :reason)
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    Page<InteractionReportJpaEntity> findCommunityPostReportsNewest(
            @Param("status") String status,
            @Param("reason") String reason,
            Pageable pageable
    );

    /**
     * Retrieves a page of community post reports sorted oldest first (created_at ASC, id ASC).
     */
    @Query("""
            SELECT r FROM InteractionReportJpaEntity r
            WHERE r.targetType = 'COMMUNITY_POST'
              AND (:status IS NULL OR r.status = :status)
              AND (:reason IS NULL OR r.reason = :reason)
            ORDER BY r.createdAt ASC, r.id ASC
            """)
    Page<InteractionReportJpaEntity> findCommunityPostReportsOldest(
            @Param("status") String status,
            @Param("reason") String reason,
            Pageable pageable
    );
}
