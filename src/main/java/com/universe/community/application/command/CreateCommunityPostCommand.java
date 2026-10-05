package com.universe.community.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Command carrying parameters required to create a new Community Post.
 */
public record CreateCommunityPostCommand(
        UUID actorUserId,
        String caption,
        UUID imageMediaAssetId
) {

    public CreateCommunityPostCommand {
        Objects.requireNonNull(actorUserId, "Actor user ID cannot be null.");
    }
}
