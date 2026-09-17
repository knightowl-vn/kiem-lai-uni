package com.universe.interaction.entry.dto;

import com.universe.interaction.application.query.CommentReadItem;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable public read representation of a comment or reply.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code id}: Comment UUID</li>
 *   <li>{@code authorUserId}: Author's user UUID</li>
 *   <li>{@code parentCommentId}: Immediate parent UUID (null for root comments)</li>
 *   <li>{@code replyToAuthorUserId}: Immediate parent author UUID when publicly attributable; null for root comments, public tombstones, and replies whose immediate-parent attribution is suppressed</li>
 *   <li>{@code body}: Plain comment text (null for tombstones)</li>
 *   <li>{@code tombstone}: {@code true} if soft-deleted, {@code false} otherwise</li>
 *   <li>{@code createdAt}: Creation timestamp</li>
 *   <li>{@code updatedAt}: Last updated timestamp</li>
 *   <li>{@code author}: Optional public author presentation</li>
 *   <li>{@code canEdit}: True if authenticated viewer owns and may edit this active comment</li>
 *   <li>{@code canDelete}: True if authenticated viewer owns and may delete this active comment</li>
 * </ul>
 */
public record CommentReadDTO(
        UUID id,
        UUID authorUserId,
        UUID parentCommentId,
        UUID replyToAuthorUserId,
        String body,
        boolean tombstone,
        Instant createdAt,
        Instant updatedAt,
        CommentAuthorDTO author,
        boolean canEdit,
        boolean canDelete
) {

    public CommentReadDTO {
        Objects.requireNonNull(id, "Comment ID cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
        Objects.requireNonNull(updatedAt, "UpdatedAt timestamp cannot be null.");

        if (tombstone) {
            authorUserId = null;
            replyToAuthorUserId = null;
            author = null;
            canEdit = false;
            canDelete = false;
        }
    }

    public CommentReadDTO(
            UUID id,
            UUID authorUserId,
            UUID parentCommentId,
            UUID replyToAuthorUserId,
            String body,
            boolean tombstone,
            Instant createdAt,
            Instant updatedAt,
            CommentAuthorDTO author,
            boolean canEdit
    ) {
        this(id, authorUserId, parentCommentId, replyToAuthorUserId, body, tombstone, createdAt, updatedAt, author, canEdit, canEdit);
    }

    public CommentReadDTO(
            UUID id,
            UUID authorUserId,
            UUID parentCommentId,
            UUID replyToAuthorUserId,
            String body,
            boolean tombstone,
            Instant createdAt,
            Instant updatedAt,
            CommentAuthorDTO author
    ) {
        this(id, authorUserId, parentCommentId, replyToAuthorUserId, body, tombstone, createdAt, updatedAt, author, false, false);
    }

    public CommentReadDTO(
            UUID id,
            UUID authorUserId,
            UUID parentCommentId,
            UUID replyToAuthorUserId,
            String body,
            boolean tombstone,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, authorUserId, parentCommentId, replyToAuthorUserId, body, tombstone, createdAt, updatedAt, null, false, false);
    }

    private static boolean isOwner(CommentReadItem item, UUID viewerUserId) {
        return item != null && viewerUserId != null && !item.tombstone() && viewerUserId.equals(item.authorUserId());
    }

    public static boolean canEdit(CommentReadItem item, UUID viewerUserId) {
        return isOwner(item, viewerUserId);
    }

    public static boolean canDelete(CommentReadItem item, UUID viewerUserId) {
        return isOwner(item, viewerUserId);
    }

    public static CommentReadDTO from(CommentReadItem item) {
        return from(item, (CommentAuthorDTO) null, (UUID) null);
    }

    public static CommentReadDTO from(CommentReadItem item, UUID viewerUserId) {
        return from(item, (CommentAuthorDTO) null, viewerUserId);
    }

    public static CommentReadDTO from(CommentReadItem item, CommentAuthorDTO author) {
        return from(item, author, (UUID) null);
    }

    public static CommentReadDTO from(CommentReadItem item, CommentAuthorDTO author, UUID viewerUserId) {
        Objects.requireNonNull(item, "CommentReadItem cannot be null.");
        if (item.tombstone()) {
            return new CommentReadDTO(
                    item.id(),
                    null,
                    item.parentCommentId(),
                    null,
                    item.body(),
                    true,
                    item.createdAt(),
                    item.updatedAt(),
                    null,
                    false,
                    false
            );
        }
        boolean eligible = isOwner(item, viewerUserId);
        return new CommentReadDTO(
                item.id(),
                item.authorUserId(),
                item.parentCommentId(),
                item.replyToAuthorUserId(),
                item.body(),
                false,
                item.createdAt(),
                item.updatedAt(),
                author,
                eligible,
                eligible
        );
    }

    public CommentReadDTO withAuthor(CommentAuthorDTO author) {
        return new CommentReadDTO(
                this.id,
                this.authorUserId,
                this.parentCommentId,
                this.replyToAuthorUserId,
                this.body,
                this.tombstone,
                this.createdAt,
                this.updatedAt,
                author,
                this.canEdit,
                this.canDelete
        );
    }

    public CommentReadDTO withCanEdit(boolean canEdit) {
        return new CommentReadDTO(
                this.id,
                this.authorUserId,
                this.parentCommentId,
                this.replyToAuthorUserId,
                this.body,
                this.tombstone,
                this.createdAt,
                this.updatedAt,
                this.author,
                canEdit,
                this.canDelete
        );
    }

    public CommentReadDTO withCanDelete(boolean canDelete) {
        return new CommentReadDTO(
                this.id,
                this.authorUserId,
                this.parentCommentId,
                this.replyToAuthorUserId,
                this.body,
                this.tombstone,
                this.createdAt,
                this.updatedAt,
                this.author,
                this.canEdit,
                canDelete
        );
    }
}
