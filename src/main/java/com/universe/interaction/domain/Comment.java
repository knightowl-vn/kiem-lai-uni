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
 *   <li>Nested reply ancestry (parentCommentId + threadRootCommentId);</li>
 *   <li>Body content validation and editing;</li>
 *   <li>Lifecycle state: {@link CommentStatus#ACTIVE} &rarr; {@link CommentStatus#DELETED} (tombstone);</li>
 *   <li>Lifecycle timestamps and temporal consistency.</li>
 * </ul>
 *
 * <p>This domain model is pure Java and framework-free.
 */
public final class Comment {

    public static final int MAX_BODY_LENGTH = 2000;

    private final UUID id;
    private final CommentTarget target;
    private final UUID authorUserId;
    private final UUID parentCommentId;
    private final UUID threadRootCommentId;
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
            UUID threadRootCommentId,
            String body,
            CommentStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt
    ) {
        this.id = Objects.requireNonNull(id, "Comment ID cannot be null.");
        this.target = Objects.requireNonNull(target, "Comment target cannot be null.");
        this.authorUserId = Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");

        if ((parentCommentId == null) != (threadRootCommentId == null)) {
            throw new IllegalArgumentException(
                    "Comment thread hierarchy mismatch: parentCommentId and threadRootCommentId must both be null or both be non-null."
            );
        }

        if (parentCommentId != null && parentCommentId.equals(id)) {
            throw new IllegalArgumentException("A comment cannot be its own parent.");
        }
        if (threadRootCommentId != null && threadRootCommentId.equals(id)) {
            throw new IllegalArgumentException("A comment cannot be its own thread root.");
        }

        this.parentCommentId = parentCommentId;
        this.threadRootCommentId = threadRootCommentId;
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
            this.body = validatePersistedBody(body);
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
        String validatedBody = validateMutationBody(body);
        return new Comment(
                id,
                target,
                authorUserId,
                null,
                null,
                validatedBody,
                CommentStatus.ACTIVE,
                createdAt,
                createdAt,
                null
        );
    }

    /**
     * Creates a new reply to an active comment (root or reply) in ACTIVE status.
     * Inherits the target from the parent comment.
     */
    public static Comment createReply(
            UUID id,
            Comment parent,
            UUID authorUserId,
            String body,
            Instant createdAt
    ) {
        Objects.requireNonNull(id, "Comment ID cannot be null.");
        Objects.requireNonNull(parent, "Parent comment cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");

        if (Objects.equals(id, parent.getId())) {
            throw new IllegalArgumentException("Reply ID cannot be the same as parent comment ID.");
        }

        if (parent.isDeleted()) {
            throw new IllegalStateException("Cannot reply to a deleted comment.");
        }

        UUID threadRootCommentId = parent.isRoot()
                ? parent.getId()
                : parent.getThreadRootCommentId();

        if (Objects.equals(id, threadRootCommentId)) {
            throw new IllegalArgumentException("Reply ID cannot be the same as thread root comment ID.");
        }

        if (createdAt.isBefore(parent.getUpdatedAt())) {
            throw new IllegalArgumentException("Reply createdAt cannot be before parent updatedAt.");
        }

        CommentTarget target = parent.getTarget();
        String validatedBody = validateMutationBody(body);

        return new Comment(
                id,
                target,
                authorUserId,
                parent.getId(),
                threadRootCommentId,
                validatedBody,
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
            UUID threadRootCommentId,
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
                threadRootCommentId,
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

        this.body = validateMutationBody(newBody);
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

    private static String validatePersistedBody(String body) {
        if (body == null) {
            throw new IllegalArgumentException("Comment body cannot be null.");
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Comment body cannot be blank.");
        }
        return trimmed;
    }

    private static String validateMutationBody(String body) {
        String trimmed = validatePersistedBody(body);
        if (trimmed.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException(
                    "Comment body cannot exceed " + MAX_BODY_LENGTH + " characters."
            );
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

    public UUID getThreadRootCommentId() {
        return threadRootCommentId;
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
                ", threadRootCommentId=" + threadRootCommentId +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", deletedAt=" + deletedAt +
                '}';
    }
}
