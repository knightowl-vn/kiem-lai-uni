package com.universe.interaction.application.mutation;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to edit the body of an existing active comment by its author.
 */
public record EditCommentCommand(
        UUID actorUserId,
        UUID commentId,
        String newBody
) {

    public EditCommentCommand {
        Objects.requireNonNull(actorUserId, "Actor user ID cannot be null.");
        Objects.requireNonNull(commentId, "Comment ID cannot be null.");
        if (newBody == null || newBody.trim().isEmpty()) {
            throw new IllegalArgumentException("Comment body cannot be null or blank.");
        }
    }
}
