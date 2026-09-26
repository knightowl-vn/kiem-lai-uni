package com.universe.interaction.infrastructure.persistence.reaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity mapping the {@code interaction_reactions} table.
 *
 * <p>Uses scalar string representations for UUIDs and enums to maintain Clean Architecture
 * boundaries and avoid ORM coupling across bounded contexts.
 */
@Entity
@Table(name = "interaction_reactions")
public class ReactionJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "user_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String userId;

    @Column(
            name = "target_type",
            nullable = false,
            length = 32
    )
    private String targetType;

    @Column(
            name = "target_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String targetId;

    @Column(
            name = "reaction_type",
            nullable = false,
            length = 20
    )
    private String reactionType;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    protected ReactionJpaEntity() {
    }

    public ReactionJpaEntity(
            String id,
            String userId,
            String targetType,
            String targetId,
            String reactionType,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.userId = userId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reactionType = reactionType;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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

    public String getReactionType() {
        return reactionType;
    }

    public void setReactionType(String reactionType) {
        this.reactionType = reactionType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReactionJpaEntity that = (ReactionJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ReactionJpaEntity{" +
                "id='" + id + '\'' +
                ", userId='" + userId + '\'' +
                ", targetType='" + targetType + '\'' +
                ", targetId='" + targetId + '\'' +
                ", reactionType='" + reactionType + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
