package com.universe.interaction.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity mapping the {@code interaction_comments} table.
 *
 * <p>Uses scalar string representations for UUIDs and enums to maintain Clean Architecture
 * boundaries and avoid ORM coupling to foreign bounded contexts (Identity, Novel, Wiki)
 * or recursive parent-child entity graphs.
 */
@Entity
@Table(name = "interaction_comments")
public class CommentJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "target_type",
            nullable = false,
            length = 20
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
            name = "author_user_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String authorUserId;

    @Column(
            name = "parent_comment_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String parentCommentId;

    @Column(
            name = "body",
            columnDefinition = "TEXT"
    )
    private String body;

    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private String status;

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

    @Column(
            name = "deleted_at"
    )
    private Instant deletedAt;

    protected CommentJpaEntity() {
    }

    public CommentJpaEntity(
            String id,
            String targetType,
            String targetId,
            String authorUserId,
            String parentCommentId,
            String body,
            String status,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt
    ) {
        this.id = id;
        this.targetType = targetType;
        this.targetId = targetId;
        this.authorUserId = authorUserId;
        this.parentCommentId = parentCommentId;
        this.body = body;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getAuthorUserId() {
        return authorUserId;
    }

    public void setAuthorUserId(String authorUserId) {
        this.authorUserId = authorUserId;
    }

    public String getParentCommentId() {
        return parentCommentId;
    }

    public void setParentCommentId(String parentCommentId) {
        this.parentCommentId = parentCommentId;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommentJpaEntity that = (CommentJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CommentJpaEntity{" +
                "id='" + id + '\'' +
                ", targetType='" + targetType + '\'' +
                ", targetId='" + targetId + '\'' +
                ", authorUserId='" + authorUserId + '\'' +
                ", parentCommentId='" + parentCommentId + '\'' +
                ", body='" + (body != null ? "[PROTECTED]" : "null") + '\'' +
                ", status='" + status + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", deletedAt=" + deletedAt +
                '}';
    }
}
