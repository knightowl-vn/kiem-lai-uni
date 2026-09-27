package com.universe.interaction.application.mutation;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to soft-delete (tombstone) a comment by its author.
 */
public record DeleteCommentCommand(
        UUID actorUserId,
        UUID commentId
) {

    public DeleteCommentCommand {
        Objects.requireNonNull(actorUserId, "Actor user ID cannot be null.");
        Objects.requireNonNull(commentId, "Comment ID cannot be null.");
    }
}
