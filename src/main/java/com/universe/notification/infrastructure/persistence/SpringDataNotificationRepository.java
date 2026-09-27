package com.universe.notification.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Spring Data JPA repository for {@link NotificationJpaEntity}.
 */
@Repository
public interface SpringDataNotificationRepository extends JpaRepository<NotificationJpaEntity, String> {

    @Query("""
            SELECT COUNT(n) FROM NotificationJpaEntity n
            WHERE n.recipientUserId = :recipientUserId
              AND n.readAt IS NULL
            """)
    long countUnreadByRecipientUserId(@Param("recipientUserId") String recipientUserId);

    @Query("""
            SELECT n FROM NotificationJpaEntity n
            WHERE n.recipientUserId = :recipientUserId
            ORDER BY n.createdAt DESC, n.id DESC
            """)
    Page<NotificationJpaEntity> findAllByRecipientUserId(
            @Param("recipientUserId") String recipientUserId,
            Pageable pageable
    );

    @Query("""
            SELECT n FROM NotificationJpaEntity n
            WHERE n.recipientUserId = :recipientUserId
              AND n.readAt IS NULL
            ORDER BY n.createdAt DESC, n.id DESC
            """)
    Page<NotificationJpaEntity> findUnreadByRecipientUserId(
            @Param("recipientUserId") String recipientUserId,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE NotificationJpaEntity n
            SET n.readAt = :now
            WHERE n.id = :id
              AND n.recipientUserId = :recipientUserId
              AND n.readAt IS NULL
            """)
    int markAsRead(
            @Param("id") String id,
            @Param("recipientUserId") String recipientUserId,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE NotificationJpaEntity n
            SET n.readAt = :now
            WHERE n.recipientUserId = :recipientUserId
              AND n.readAt IS NULL
            """)
    int markAllAsRead(
            @Param("recipientUserId") String recipientUserId,
            @Param("now") Instant now
    );

    /**
     * Native MySQL duplicate-safe idempotent insert.
     *
     * <p>Uses {@code ON DUPLICATE KEY UPDATE dedupe_key = dedupe_key} to avoid throwing
     * {@code DataIntegrityViolationException} on duplicate {@code dedupe_key}, preventing
     * rollback-only poisoning of active producer transactions while preserving all original columns.
     * Non-duplicate SQL errors (e.g. check constraints, schema errors) fail normally.
     */
    @Modifying
    @Query(
            value = """
                    INSERT INTO notifications (
                        id,
                        recipient_user_id,
                        type,
                        actor_user_id,
                        actor_display_name_snapshot,
                        target_type,
                        target_id,
                        target_title_snapshot,
                        comment_id,
                        thread_root_id,
                        detail_snapshot,
                        dedupe_key,
                        read_at,
                        created_at
                    ) VALUES (
                        :id,
                        :recipientUserId,
                        :type,
                        :actorUserId,
                        :actorDisplayNameSnapshot,
                        :targetType,
                        :targetId,
                        :targetTitleSnapshot,
                        :commentId,
                        :threadRootId,
                        :detailSnapshot,
                        :dedupeKey,
                        :readAt,
                        :createdAt
                    )
                    ON DUPLICATE KEY UPDATE dedupe_key = dedupe_key
                    """,
            nativeQuery = true
    )
    int insertIdempotent(
            @Param("id") String id,
            @Param("recipientUserId") String recipientUserId,
            @Param("type") String type,
            @Param("actorUserId") String actorUserId,
            @Param("actorDisplayNameSnapshot") String actorDisplayNameSnapshot,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("targetTitleSnapshot") String targetTitleSnapshot,
            @Param("commentId") String commentId,
            @Param("threadRootId") String threadRootId,
            @Param("detailSnapshot") String detailSnapshot,
            @Param("dedupeKey") String dedupeKey,
            @Param("readAt") Instant readAt,
            @Param("createdAt") Instant createdAt
    );
}
