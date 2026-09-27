package com.universe.interaction.contracts.dto.authored;

import com.universe.interaction.domain.CommentTargetType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Focused read DTO representing a single comment authored by a user.
 */
public record AuthoredCommentItemDTO(
        UUID commentId,
        CommentTargetType targetType,
        UUID targetId,
        String body,
        Instant createdAt,
        Instant updatedAt,
        UUID parentCommentId,
        UUID threadRootCommentId
) {
    public AuthoredCommentItemDTO {
        Objects.requireNonNull(commentId, "commentId cannot be null.");
        Objects.requireNonNull(targetType, "targetType cannot be null.");
        Objects.requireNonNull(targetId, "targetId cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
    }

    public boolean isReply() {
        return parentCommentId != null;
    }

    public boolean isRoot() {
        return parentCommentId == null;
    }
}
