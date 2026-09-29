package com.universe.community.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Command carrying parameters required to edit an existing Community Post's caption.
 */
public record EditCommunityPostCaptionCommand(
        UUID postId,
        UUID actorUserId,
        String newCaption
) {

    public EditCommunityPostCaptionCommand {
        Objects.requireNonNull(postId, "Post ID cannot be null.");
        Objects.requireNonNull(actorUserId, "Actor user ID cannot be null.");
    }
}
