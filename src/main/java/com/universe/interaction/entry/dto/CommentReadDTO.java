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
        Instant updatedAt
) {

    public static CommentReadDTO from(CommentReadItem item) {
        Objects.requireNonNull(item, "CommentReadItem cannot be null.");
        return new CommentReadDTO(
                item.id(),
                item.authorUserId(),
                item.parentCommentId(),
                item.replyToAuthorUserId(),
                item.body(),
                item.tombstone(),
                item.createdAt(),
                item.updatedAt()
        );
    }
}
