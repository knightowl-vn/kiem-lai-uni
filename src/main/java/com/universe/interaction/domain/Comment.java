package com.universe.interaction.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing an Interaction Comment or Reply.
 *
 * <p>Manages:
 * <ul>
 *   <li>Stable UUID identity;</li>
 *   <li>Generic comment target (targetType + scalar targetId);</li>
 *   <li>Author user ID (scalar UUID);</li>
 *   <li>V1 single-depth hierarchy (root comments vs direct replies);</li>
 *   <li>Body content validation and editing;</li>
 *   <li>Lifecycle state: {@link CommentStatus#ACTIVE} &rarr; {@link CommentStatus#DELETED} (tombstone);</li>
 *   <li>Lifecycle timestamps and temporal consistency.</li>
 * </ul>
 *
 * <p>This domain model is pure Java and framework-free.
 */
public final class Comment {

    private final UUID id;
    private final CommentTarget target;
    private final UUID authorUserId;
    private final UUID parentCommentId;
    private String body;
    private CommentStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    private Comment(
            UUID id,
            CommentTarget target,
            UUID authorUserId,
            UUID parentCommentId,
            String body,
            CommentStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt
    ) {
        this.id = Objects.requireNonNull(id, "Comment ID cannot be null.");
        this.target = Objects.requireNonNull(target, "Comment target cannot be null.");
        this.authorUserId = Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        if (parentCommentId != null && parentCommentId.equals(id)) {
            throw new IllegalArgumentException("A comment cannot be its own parent.");
        }
        this.parentCommentId = parentCommentId;
        this.status = Objects.requireNonNull(status, "Comment status cannot be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "UpdatedAt timestamp cannot be null.");
        this.deletedAt = deletedAt;

        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }

        if (this.status == CommentStatus.DELETED) {
            if (body != null) {
                throw new IllegalArgumentException("Body must be null for a DELETED comment.");
            }
            this.body = null;
            if (this.deletedAt == null) {
                throw new IllegalArgumentException("DeletedAt timestamp cannot be null for a DELETED comment.");
            }
            if (!Objects.equals(this.updatedAt, this.deletedAt)) {
                throw new IllegalArgumentException("UpdatedAt timestamp must equal deletedAt timestamp for a DELETED comment.");
            }
            if (this.deletedAt.isBefore(this.createdAt)) {
                throw new IllegalArgumentException("DeletedAt timestamp cannot be before createdAt timestamp.");
            }
        } else {
            this.body = validateBody(body);
            if (this.deletedAt != null) {
                throw new IllegalArgumentException("DeletedAt timestamp must be null for an ACTIVE comment.");
            }
        }
    }

    /**
     * Creates a new root comment in ACTIVE status.
     */
    public static Comment createRoot(
            UUID id,
            CommentTarget target,
            UUID authorUserId,
            String body,
            Instant createdAt
    ) {
        return new Comment(
                id,
                target,
                authorUserId,
                null,
                body,
                CommentStatus.ACTIVE,
                createdAt,
                createdAt,
                null
        );
    }

    /**
     * Creates a new reply to an active root comment in ACTIVE status.
     * Inherits the target from the root comment.
     */
    public static Comment createReply(
            UUID id,
            Comment root,
            UUID authorUserId,
            String body,
            Instant createdAt
    ) {
        Objects.requireNonNull(root, "Root comment cannot be null.");
        return createReply(id, root, root.getTarget(), authorUserId, body, createdAt);
    }

    /**
     * Creates a new reply to an active root comment with an explicit target.
     * The target must match the root comment's target.
     */
    public static Comment createReply(
            UUID id,
            Comment root,
            CommentTarget target,
            UUID authorUserId,
            String body,
            Instant createdAt
    ) {
        Objects.requireNonNull(id, "Comment ID cannot be null.");
        Objects.requireNonNull(root, "Root comment cannot be null.");
        Objects.requireNonNull(target, "Comment target cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");

        if (Objects.equals(id, root.getId())) {
            throw new IllegalArgumentException("Reply ID cannot be the same as root comment ID.");
        }

        if (!root.isRoot()) {
            throw new IllegalArgumentException("Cannot reply to a reply. Only root comments can receive replies.");
        }

        if (root.isDeleted()) {
            throw new IllegalStateException("Cannot reply to a deleted root comment.");
        }

        if (!Objects.equals(target, root.getTarget())) {
            throw new IllegalArgumentException("Reply target must match root comment target.");
        }

        if (createdAt.isBefore(root.getUpdatedAt())) {
            throw new IllegalArgumentException("Reply createdAt cannot be before root updatedAt.");
        }

        return new Comment(
                id,
                target,
                authorUserId,
                root.getId(),
                body,
                CommentStatus.ACTIVE,
                createdAt,
                createdAt,
                null
        );
    }

    /**
     * Rehydrates a Comment from persistence.
     */
    public static Comment rehydrate(
            UUID id,
            CommentTarget target,
            UUID authorUserId,
            UUID parentCommentId,
            String body,
            CommentStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt
    ) {
        return new Comment(
                id,
                target,
                authorUserId,
                parentCommentId,
                body,
                status,
                createdAt,
                updatedAt,
                deletedAt
        );
    }

    /**
     * Edits the body of an ACTIVE comment.
     */
    public void edit(
            String newBody,
            Instant editedAt
    ) {
        if (this.status == CommentStatus.DELETED) {
            throw new IllegalStateException("Cannot edit a deleted comment.");
        }

        Objects.requireNonNull(editedAt, "Edit timestamp cannot be null.");

        if (editedAt.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("Edit timestamp cannot be before the last updated timestamp.");
        }

        this.body = validateBody(newBody);
        this.updatedAt = editedAt;
    }

    /**
     * Marks the comment as DELETED (tombstone).
     * Idempotent if already deleted.
     */
    public void delete(
            Instant deletedAt
    ) {
        Objects.requireNonNull(deletedAt, "Delete timestamp cannot be null.");

        if (this.status == CommentStatus.DELETED) {
            return;
        }

        if (deletedAt.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("Delete timestamp cannot be before the last updated timestamp.");
        }

        this.status = CommentStatus.DELETED;
        this.deletedAt = deletedAt;
        this.updatedAt = deletedAt;
        this.body = null;
    }

    private static String validateBody(String body) {
        if (body == null) {
            throw new IllegalArgumentException("Comment body cannot be null.");
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Comment body cannot be blank.");
        }
        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public CommentTarget getTarget() {
        return target;
    }

    public CommentTargetType getTargetType() {
        return target.type();
    }

    public UUID getTargetId() {
        return target.targetId();
    }

    public UUID getAuthorUserId() {
        return authorUserId;
    }

    public UUID getParentCommentId() {
        return parentCommentId;
    }

    public String getBody() {
        return body;
    }

    public CommentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public boolean isRoot() {
        return parentCommentId == null;
    }

    public boolean isReply() {
        return parentCommentId != null;
    }

    public boolean isActive() {
        return status == CommentStatus.ACTIVE;
    }

    public boolean isDeleted() {
        return status == CommentStatus.DELETED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Comment comment = (Comment) o;
        return Objects.equals(id, comment.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Comment{" +
                "id=" + id +
                ", target=" + target +
                ", authorUserId=" + authorUserId +
                ", parentCommentId=" + parentCommentId +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", deletedAt=" + deletedAt +
                '}';
    }
}
