package com.universe.notification.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity representing the {@code notifications} table.
 */
@Entity
@Table(name = "notifications")
public class NotificationJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "recipient_user_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String recipientUserId;

    @Column(
            name = "type",
            nullable = false,
            length = 40
    )
    private String type;

    @Column(
            name = "actor_user_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String actorUserId;

    @Column(
            name = "actor_display_name_snapshot",
            length = 100
    )
    private String actorDisplayNameSnapshot;

    @Column(
            name = "target_type",
            length = 40
    )
    private String targetType;

    @Column(
            name = "target_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String targetId;

    @Column(
            name = "target_title_snapshot",
            length = 255
    )
    private String targetTitleSnapshot;

    @Column(
            name = "comment_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String commentId;

    @Column(
            name = "thread_root_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String threadRootId;

    @Column(
            name = "detail_snapshot",
            length = 2000
    )
    private String detailSnapshot;

    @Column(
            name = "dedupe_key",
            nullable = false,
            length = 191,
            unique = true
    )
    private String dedupeKey;

    @Column(
            name = "read_at"
    )
    private Instant readAt;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    protected NotificationJpaEntity() {
    }

    public NotificationJpaEntity(
            String id,
            String recipientUserId,
            String type,
            String actorUserId,
            String actorDisplayNameSnapshot,
            String targetType,
            String targetId,
            String targetTitleSnapshot,
            String commentId,
            String threadRootId,
            String detailSnapshot,
            String dedupeKey,
            Instant readAt,
            Instant createdAt
    ) {
        this.id = id;
        this.recipientUserId = recipientUserId;
        this.type = type;
        this.actorUserId = actorUserId;
        this.actorDisplayNameSnapshot = actorDisplayNameSnapshot;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetTitleSnapshot = targetTitleSnapshot;
        this.commentId = commentId;
        this.threadRootId = threadRootId;
        this.detailSnapshot = detailSnapshot;
        this.dedupeKey = dedupeKey;
        this.readAt = readAt;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRecipientUserId() {
        return recipientUserId;
    }

    public void setRecipientUserId(String recipientUserId) {
        this.recipientUserId = recipientUserId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(String actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getActorDisplayNameSnapshot() {
        return actorDisplayNameSnapshot;
    }

    public void setActorDisplayNameSnapshot(String actorDisplayNameSnapshot) {
        this.actorDisplayNameSnapshot = actorDisplayNameSnapshot;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getTargetTitleSnapshot() {
        return targetTitleSnapshot;
    }

    public void setTargetTitleSnapshot(String targetTitleSnapshot) {
        this.targetTitleSnapshot = targetTitleSnapshot;
    }

    public String getCommentId() {
        return commentId;
    }

    public void setCommentId(String commentId) {
        this.commentId = commentId;
    }

    public String getThreadRootId() {
        return threadRootId;
    }

    public void setThreadRootId(String threadRootId) {
        this.threadRootId = threadRootId;
    }

    public String getDetailSnapshot() {
        return detailSnapshot;
    }

    public void setDetailSnapshot(String detailSnapshot) {
        this.detailSnapshot = detailSnapshot;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NotificationJpaEntity that = (NotificationJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
