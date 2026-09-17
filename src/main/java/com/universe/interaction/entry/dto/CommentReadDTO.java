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
 *   <li>{@code replyToAuthorUserId}: Immediate parent author UUID (null for root comments)</li>
 *   <li>{@code body}: Plain comment text (null for tombstones)</li>
 *   <li>{@code tombstone}: {@code true} if soft-deleted, {@code false} otherwise</li>
 *   <li>{@code createdAt}: Creation timestamp</li>
 *   <li>{@code updatedAt}: Last updated timestamp</li>
 *   <li>{@code author}: Optional public author presentation</li>
 *   <li>{@code canEdit}: True if authenticated viewer owns and may edit this active comment</li>
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
        boolean canEdit
) {

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
        this(id, authorUserId, parentCommentId, replyToAuthorUserId, body, tombstone, createdAt, updatedAt, author, false);
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
        this(id, authorUserId, parentCommentId, replyToAuthorUserId, body, tombstone, createdAt, updatedAt, null, false);
    }

    public static boolean canEdit(CommentReadItem item, UUID viewerUserId) {
        return item != null && viewerUserId != null && !item.tombstone() && viewerUserId.equals(item.authorUserId());
    }

    public static CommentReadDTO from(CommentReadItem item) {
        return from(item, (CommentAuthorDTO) null, false);
    }

    public static CommentReadDTO from(CommentReadItem item, UUID viewerUserId) {
        return from(item, (CommentAuthorDTO) null, canEdit(item, viewerUserId));
    }

    public static CommentReadDTO from(CommentReadItem item, CommentAuthorDTO author) {
        return from(item, author, false);
    }

    public static CommentReadDTO from(CommentReadItem item, CommentAuthorDTO author, UUID viewerUserId) {
        return from(item, author, canEdit(item, viewerUserId));
    }

    public static CommentReadDTO from(CommentReadItem item, CommentAuthorDTO author, boolean canEdit) {
        Objects.requireNonNull(item, "CommentReadItem cannot be null.");
        return new CommentReadDTO(
                item.id(),
                item.authorUserId(),
                item.parentCommentId(),
                item.replyToAuthorUserId(),
                item.body(),
                item.tombstone(),
                item.createdAt(),
                item.updatedAt(),
                author,
                canEdit
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
                this.canEdit
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
                canEdit
        );
    }
}
