package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
import com.universe.interaction.domain.Comment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable application read model representing a comment or reply in a discussion thread.
 *
 * <p>Preserves clean architecture boundaries:
 * <ul>
 *   <li>Interaction-owned: zero dependency on Identity, Novel, or Wiki models;</li>
 *   <li>Scalar cross-context references: uses {@code UUID} for user and parent identities;</li>
 *   <li>Tombstone integrity: deleted replies retain ancestry and timestamps with {@code body = null};</li>
 *   <li>Immediate attribution: {@code replyToAuthorUserId} reflects the immediate parent's author.</li>
 * </ul>
 */
public record CommentReadItem(
        UUID id,
        UUID authorUserId,
        UUID parentCommentId,
        UUID replyToAuthorUserId,
        String body,
        boolean tombstone,
        Instant createdAt,
        Instant updatedAt
) {

    public CommentReadItem {
        Objects.requireNonNull(id, "Comment ID cannot be null.");
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
        Objects.requireNonNull(updatedAt, "UpdatedAt timestamp cannot be null.");

        if (tombstone) {
            if (body != null) {
                throw new IllegalArgumentException("Body must be null for tombstone item: " + id);
            }
        } else {
            if (body == null) {
                throw new IllegalArgumentException("Body cannot be null for active item: " + id);
            }
        }

        if (parentCommentId == null) {
            if (replyToAuthorUserId != null) {
                throw new IllegalArgumentException("Root comment cannot have replyToAuthorUserId: " + id);
            }
            if (tombstone) {
                throw new IllegalArgumentException("Root comment cannot be a tombstone: " + id);
            }
        }
        // Note: For replies (parentCommentId != null), replyToAuthorUserId may be null when the immediate parent
        // is deleted/tombstoned so that deleted-parent author attribution is intentionally suppressed for privacy.
    }

    /**
     * Creates a read item from an active root comment aggregate.
     */
    public static CommentReadItem fromRoot(Comment root) {
        Objects.requireNonNull(root, "Root comment cannot be null.");
        if (!root.isRoot()) {
            throw new CommentThreadIntegrityException("Comment is not a root comment: " + root.getId());
        }
        if (root.isDeleted()) {
            throw new IllegalArgumentException("Deleted root comment cannot be mapped to CommentReadItem: " + root.getId());
        }
        return new CommentReadItem(
                root.getId(),
                root.getAuthorUserId(),
                null,
                null,
                root.getBody(),
                false,
                root.getCreatedAt(),
                root.getUpdatedAt()
        );
    }

    /**
     * Creates a read item from an active reply comment aggregate with immediate parent attribution.
     */
    public static CommentReadItem fromActiveReply(Comment reply, UUID replyToAuthorUserId) {
        Objects.requireNonNull(reply, "Reply comment cannot be null.");
        if (!reply.isReply()) {
            throw new CommentThreadIntegrityException("Comment is not a reply: " + reply.getId());
        }
        if (!reply.isActive()) {
            throw new IllegalArgumentException("Reply is not active: " + reply.getId());
        }
        return new CommentReadItem(
                reply.getId(),
                reply.getAuthorUserId(),
                reply.getParentCommentId(),
                replyToAuthorUserId,
                reply.getBody(),
                false,
                reply.getCreatedAt(),
                reply.getUpdatedAt()
        );
    }

    /**
     * Creates a read item from a deleted tombstone reply aggregate with immediate parent attribution.
     */
    public static CommentReadItem fromTombstoneReply(Comment reply, UUID replyToAuthorUserId) {
        Objects.requireNonNull(reply, "Reply comment cannot be null.");
        if (!reply.isReply()) {
            throw new CommentThreadIntegrityException("Comment is not a reply: " + reply.getId());
        }
        if (!reply.isDeleted()) {
            throw new IllegalArgumentException("Reply is not deleted: " + reply.getId());
        }
        return new CommentReadItem(
                reply.getId(),
                reply.getAuthorUserId(),
                reply.getParentCommentId(),
                replyToAuthorUserId,
                null,
                true,
                reply.getCreatedAt(),
                reply.getUpdatedAt()
        );
    }

    public UUID getId() {
        return id();
    }

    public UUID getAuthorUserId() {
        return authorUserId();
    }

    public UUID getParentCommentId() {
        return parentCommentId();
    }

    public UUID getReplyToAuthorUserId() {
        return replyToAuthorUserId();
    }

    public String getBody() {
        return body();
    }

    public boolean isTombstone() {
        return tombstone();
    }

    public Instant getCreatedAt() {
        return createdAt();
    }

    public Instant getUpdatedAt() {
        return updatedAt();
    }

    public boolean isRoot() {
        return parentCommentId == null;
    }

    public boolean isReply() {
        return parentCommentId != null;
    }
}
