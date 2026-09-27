package com.universe.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Notification Aggregate Domain Tests")
class NotificationTest {

    @Test
    @DisplayName("Successfully creates unread notification with valid arguments")
    void createsUnreadNotificationSuccessfully() {
        UUID id = UUID.randomUUID();
        UUID recipientUserId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        Instant now = Instant.now();

        Notification notification = Notification.create(
                id,
                recipientUserId,
                NotificationType.COMMENT_REPLY,
                actorUserId,
                "Actor User",
                "NOVEL_CHAPTER",
                targetId,
                "Chapter 1",
                commentId,
                null,
                null,
                "comment-reply:" + commentId,
                now
        );

        assertThat(notification.getId()).isEqualTo(id);
        assertThat(notification.getRecipientUserId()).isEqualTo(recipientUserId);
        assertThat(notification.getType()).isEqualTo(NotificationType.COMMENT_REPLY);
        assertThat(notification.getActorUserId()).isEqualTo(actorUserId);
        assertThat(notification.getActorDisplayNameSnapshot()).isEqualTo("Actor User");
        assertThat(notification.getTargetType()).isEqualTo("NOVEL_CHAPTER");
        assertThat(notification.getTargetId()).isEqualTo(targetId);
        assertThat(notification.getTargetTitleSnapshot()).isEqualTo("Chapter 1");
        assertThat(notification.getCommentId()).isEqualTo(commentId);
        assertThat(notification.getDetailSnapshot()).isNull();
        assertThat(notification.getDedupeKey()).isEqualTo("comment-reply:" + commentId);
        assertThat(notification.isUnread()).isTrue();
        assertThat(notification.isRead()).isFalse();
        assertThat(notification.getReadAt()).isNull();
        assertThat(notification.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("COMMENT_REPLY must not persist comment/reply body in detailSnapshot (Hard-delete privacy rule)")
    void commentReplyDetailSnapshotPrivacyInvariant() {
        UUID id = UUID.randomUUID();
        UUID recipientUserId = UUID.randomUUID();

        assertThatThrownBy(() -> Notification.create(
                id,
                recipientUserId,
                NotificationType.COMMENT_REPLY,
                UUID.randomUUID(),
                "Actor",
                "NOVEL_CHAPTER",
                UUID.randomUUID(),
                "Title",
                UUID.randomUUID(),
                null,
                "Private comment text that should not be persisted",
                "dedupe-1",
                Instant.now()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COMMENT_REPLY notification cannot persist detail snapshot text.");
    }

    @Test
    @DisplayName("Non-comment notifications allow detailSnapshot (e.g., reviewer feedback or summary)")
    void wikiContributionAllowsDetailSnapshot() {
        UUID id = UUID.randomUUID();
        UUID recipientUserId = UUID.randomUUID();
        Instant now = Instant.now();

        Notification notification = Notification.create(
                id,
                recipientUserId,
                NotificationType.WIKI_CONTRIBUTION_REJECTED,
                UUID.randomUUID(),
                "Admin Reviewer",
                "WIKI_CONTRIBUTION",
                UUID.randomUUID(),
                "Contribution Title",
                null,
                null,
                "Cần bổ sung thêm nguồn tham khảo chính xác.",
                "wiki-contrib:reject:123",
                now
        );

        assertThat(notification.getDetailSnapshot()).isEqualTo("Cần bổ sung thêm nguồn tham khảo chính xác.");
    }

    @Test
    @DisplayName("markRead transitions unread notification to read and is idempotent")
    void markReadIsIdempotent() {
        UUID id = UUID.randomUUID();
        UUID recipientUserId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-09-27T08:00:00Z");
        Instant t1 = Instant.parse("2026-09-27T08:30:00Z");
        Instant t2 = Instant.parse("2026-09-27T09:00:00Z");

        Notification notification = Notification.create(
                id,
                recipientUserId,
                NotificationType.WIKI_CONTRIBUTION_RESOLVED,
                null,
                null,
                "WIKI_ARTICLE",
                UUID.randomUUID(),
                "Article Title",
                null,
                null,
                null,
                "wiki-resolve:123",
                t0
        );

        assertThat(notification.isUnread()).isTrue();
        notification.markRead(t1);
        assertThat(notification.isRead()).isTrue();
        assertThat(notification.getReadAt()).isEqualTo(t1);

        // Idempotent secondary markRead must not overwrite t1 with t2
        notification.markRead(t2);
        assertThat(notification.getReadAt()).isEqualTo(t1);
    }

    @Test
    @DisplayName("Validation fails when required fields are missing")
    void validationFailsOnMissingRequiredFields() {
        UUID validId = UUID.randomUUID();
        UUID validRecipient = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> Notification.create(
                null, validRecipient, NotificationType.COMMENT_REPLY,
                null, null, null, null, null, null, null, null, "dedupe", now
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> Notification.create(
                validId, null, NotificationType.COMMENT_REPLY,
                null, null, null, null, null, null, null, null, "dedupe", now
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> Notification.create(
                validId, validRecipient, null,
                null, null, null, null, null, null, null, null, "dedupe", now
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> Notification.create(
                validId, validRecipient, NotificationType.COMMENT_REPLY,
                null, null, null, null, null, null, null, null, "   ", now
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
