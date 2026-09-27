package com.universe.interaction.application.mutation;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to reply to an existing root or nested reply comment.
 */
public record ReplyCommentCommand(
        UUID actorUserId,
        UUID parentCommentId,
        String body
) {

    public ReplyCommentCommand {
        Objects.requireNonNull(actorUserId, "Actor user ID cannot be null.");
        Objects.requireNonNull(parentCommentId, "Parent comment ID cannot be null.");
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("Reply body cannot be null or blank.");
        }
    }
}
