package com.universe.interaction.application.mutation;

import com.universe.interaction.domain.CommentTarget;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to create a new root comment on an eligible target.
 */
public record CreateRootCommentCommand(
        UUID actorUserId,
        CommentTarget target,
        String body
) {

    public CreateRootCommentCommand {
        Objects.requireNonNull(actorUserId, "Actor user ID cannot be null.");
        Objects.requireNonNull(target, "Comment target cannot be null.");
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("Comment body cannot be null or blank.");
        }
    }
}
