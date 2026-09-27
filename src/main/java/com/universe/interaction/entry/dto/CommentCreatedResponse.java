package com.universe.interaction.entry.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Minimal immutable response payload returned upon successful creation of a comment or reply.
 */
public record CommentCreatedResponse(
        UUID commentId
) {
    public CommentCreatedResponse {
        Objects.requireNonNull(commentId, "Comment ID cannot be null.");
    }
}
