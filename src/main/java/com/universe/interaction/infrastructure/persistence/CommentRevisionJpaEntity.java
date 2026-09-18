package com.universe.interaction.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity mapping the {@code interaction_comment_revisions} table.
 *
 * <p>Uses scalar string representations for UUIDs to maintain Clean Architecture
 * boundaries and avoid ORM coupling to parent {@link CommentJpaEntity}.
 */
@Entity
@Table(name = "interaction_comment_revisions")
public class CommentRevisionJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "comment_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String commentId;

    @Column(
            name = "revision_number",
            nullable = false
    )
    private int revisionNumber;

    @Column(
            name = "body",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String body;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    protected CommentRevisionJpaEntity() {
    }

    public CommentRevisionJpaEntity(
            String id,
            String commentId,
            int revisionNumber,
            String body,
            Instant createdAt
    ) {
        this.id = id;
        this.commentId = commentId;
        this.revisionNumber = revisionNumber;
        this.body = body;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCommentId() {
        return commentId;
    }

    public void setCommentId(String commentId) {
        this.commentId = commentId;
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public void setRevisionNumber(int revisionNumber) {
        this.revisionNumber = revisionNumber;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
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
        CommentRevisionJpaEntity that = (CommentRevisionJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CommentRevisionJpaEntity{" +
                "id='" + id + '\'' +
                ", commentId='" + commentId + '\'' +
                ", revisionNumber=" + revisionNumber +
                ", body='" + (body != null ? "[PROTECTED]" : "null") + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
