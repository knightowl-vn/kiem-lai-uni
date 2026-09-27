package com.universe.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing an in-app user notification event.
 *
 * <p>Invariants & Privacy Rules:
 * <ul>
 *   <li>Immutable event data: notification type, timestamps, actor snapshot, target references cannot be mutated;</li>
 *   <li>Single source of truth for read state: {@code readAt == null} is unread, {@code readAt != null} is read;</li>
 *   <li>Hard-Delete Privacy Invariant: {@link NotificationType#COMMENT_REPLY} notifications MUST NOT store
 *       reply or comment body text in {@code detailSnapshot}. It must always be null;</li>
 *   <li>Idempotent state mutation: {@link #markRead(Instant)} is an idempotent operation.</li>
 * </ul>
 */
public class Notification {

    public static final int MAX_ACTOR_DISPLAY_NAME_LENGTH = 100;
    public static final int MAX_TARGET_TYPE_LENGTH = 40;
    public static final int MAX_TARGET_TITLE_LENGTH = 255;
    public static final int MAX_DETAIL_SNAPSHOT_LENGTH = 2000;
    public static final int MAX_DEDUPE_KEY_LENGTH = 191;

    private final UUID id;
    private final UUID recipientUserId;
    private final NotificationType type;
    private final UUID actorUserId;
    private final String actorDisplayNameSnapshot;
    private final String targetType;
    private final UUID targetId;
    private final String targetTitleSnapshot;
    private final UUID commentId;
    private final UUID threadRootId;
    private final String detailSnapshot;
    private final String dedupeKey;
    private Instant readAt;
    private final Instant createdAt;

    public Notification(
            UUID id,
            UUID recipientUserId,
            NotificationType type,
            UUID actorUserId,
            String actorDisplayNameSnapshot,
            String targetType,
            UUID targetId,
            String targetTitleSnapshot,
            UUID commentId,
            UUID threadRootId,
            String detailSnapshot,
            String dedupeKey,
            Instant readAt,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "Notification ID cannot be null.");
        this.recipientUserId = Objects.requireNonNull(recipientUserId, "Recipient user ID cannot be null.");
        this.type = Objects.requireNonNull(type, "Notification type cannot be null.");

        if (dedupeKey == null || dedupeKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Dedupe key cannot be blank.");
        }
        String trimmedDedupeKey = dedupeKey.trim();
        if (trimmedDedupeKey.length() > MAX_DEDUPE_KEY_LENGTH) {
            throw new IllegalArgumentException("Dedupe key exceeds max length of " + MAX_DEDUPE_KEY_LENGTH);
        }
        this.dedupeKey = trimmedDedupeKey;

        this.actorUserId = actorUserId;
        this.actorDisplayNameSnapshot = sanitizeOptionalString(actorDisplayNameSnapshot, MAX_ACTOR_DISPLAY_NAME_LENGTH, "Actor display name");
        this.targetType = sanitizeOptionalString(targetType, MAX_TARGET_TYPE_LENGTH, "Target type");
        this.targetId = targetId;
        this.targetTitleSnapshot = sanitizeOptionalString(targetTitleSnapshot, MAX_TARGET_TITLE_LENGTH, "Target title");
        this.commentId = commentId;
        this.threadRootId = threadRootId;

        // Hard-delete privacy invariant: COMMENT_REPLY must never persist comment/reply body
        if (type == NotificationType.COMMENT_REPLY && detailSnapshot != null && !detailSnapshot.trim().isEmpty()) {
            throw new IllegalArgumentException("COMMENT_REPLY notification cannot persist detail snapshot text.");
        }
        this.detailSnapshot = sanitizeOptionalString(detailSnapshot, MAX_DETAIL_SNAPSHOT_LENGTH, "Detail snapshot");

        this.readAt = readAt;
        this.createdAt = Objects.requireNonNull(createdAt, "Created at timestamp cannot be null.");
    }

    public static Notification create(
            UUID id,
            UUID recipientUserId,
            NotificationType type,
            UUID actorUserId,
            String actorDisplayNameSnapshot,
            String targetType,
            UUID targetId,
            String targetTitleSnapshot,
            UUID commentId,
            UUID threadRootId,
            String detailSnapshot,
            String dedupeKey,
            Instant now
    ) {
        Instant timestamp = Objects.requireNonNull(now, "Creation timestamp cannot be null.");
        return new Notification(
                id,
                recipientUserId,
                type,
                actorUserId,
                actorDisplayNameSnapshot,
                targetType,
                targetId,
                targetTitleSnapshot,
                commentId,
                threadRootId,
                detailSnapshot,
                dedupeKey,
                null,
                timestamp
        );
    }

    public static Notification reconstitute(
            UUID id,
            UUID recipientUserId,
            NotificationType type,
            UUID actorUserId,
            String actorDisplayNameSnapshot,
            String targetType,
            UUID targetId,
            String targetTitleSnapshot,
            UUID commentId,
            UUID threadRootId,
            String detailSnapshot,
            String dedupeKey,
            Instant readAt,
            Instant createdAt
    ) {
        return new Notification(
                id,
                recipientUserId,
                type,
                actorUserId,
                actorDisplayNameSnapshot,
                targetType,
                targetId,
                targetTitleSnapshot,
                commentId,
                threadRootId,
                detailSnapshot,
                dedupeKey,
                readAt,
                createdAt
        );
    }

    public boolean isUnread() {
        return readAt == null;
    }

    public boolean isRead() {
        return readAt != null;
    }

    public void markRead(Instant now) {
        if (this.readAt == null) {
            this.readAt = Objects.requireNonNull(now, "Read at timestamp cannot be null.");
        }
    }

    private static String sanitizeOptionalString(String value, int maxLength, String fieldName) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " exceeds max length of " + maxLength);
        }
        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRecipientUserId() {
        return recipientUserId;
    }

    public NotificationType getType() {
        return type;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getActorDisplayNameSnapshot() {
        return actorDisplayNameSnapshot;
    }

    public String getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getTargetTitleSnapshot() {
        return targetTitleSnapshot;
    }

    public UUID getCommentId() {
        return commentId;
    }

    public UUID getThreadRootId() {
        return threadRootId;
    }

    public String getDetailSnapshot() {
        return detailSnapshot;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Notification that = (Notification) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Notification{" +
                "id=" + id +
                ", recipientUserId=" + recipientUserId +
                ", type=" + type +
                ", actorDisplayNameSnapshot='" + actorDisplayNameSnapshot + '\'' +
                ", targetType='" + targetType + '\'' +
                ", targetTitleSnapshot='" + targetTitleSnapshot + '\'' +
                ", dedupeKey='" + dedupeKey + '\'' +
                ", isUnread=" + isUnread() +
                ", createdAt=" + createdAt +
                '}';
    }
}
