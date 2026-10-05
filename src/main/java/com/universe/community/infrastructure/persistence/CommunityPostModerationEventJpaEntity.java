package com.universe.community.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code community_post_moderation_events} append-only table.
 */
@Entity
@Table(name = "community_post_moderation_events")
public class CommunityPostModerationEventJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "post_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String postId;

    @Column(
            name = "action",
            nullable = false,
            length = 32,
            columnDefinition = "VARCHAR(32)"
    )
    private String action;

    @Column(
            name = "from_status",
            nullable = false,
            length = 32,
            columnDefinition = "VARCHAR(32)"
    )
    private String fromStatus;

    @Column(
            name = "to_status",
            nullable = false,
            length = 32,
            columnDefinition = "VARCHAR(32)"
    )
    private String toStatus;

    @Column(
            name = "moderator_user_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String moderatorUserId;

    @Column(
            name = "reason",
            length = 500,
            columnDefinition = "VARCHAR(500)"
    )
    private String reason;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    protected CommunityPostModerationEventJpaEntity() {
    }

    public CommunityPostModerationEventJpaEntity(
            String id,
            String postId,
            String action,
            String fromStatus,
            String toStatus,
            String moderatorUserId,
            String reason,
            Instant createdAt
    ) {
        this.id = id;
        this.postId = postId;
        this.action = action;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.moderatorUserId = moderatorUserId;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getPostId() {
        return postId;
    }

    public String getAction() {
        return action;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public String getModeratorUserId() {
        return moderatorUserId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommunityPostModerationEventJpaEntity that = (CommunityPostModerationEventJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CommunityPostModerationEventJpaEntity{" +
                "id='" + id + '\'' +
                ", postId='" + postId + '\'' +
                ", action='" + action + '\'' +
                ", fromStatus='" + fromStatus + '\'' +
                ", toStatus='" + toStatus + '\'' +
                ", moderatorUserId='" + moderatorUserId + '\'' +
                ", reason='" + reason + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
