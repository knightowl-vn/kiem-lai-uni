package com.universe.interaction.application.mutation;

import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to set a desired emotional reaction for an authenticated user on a target.
 */
public record SetReactionCommand(
        UUID userId,
        ReactionTarget target,
        ReactionType reactionType
) {
    public SetReactionCommand {
        Objects.requireNonNull(userId, "User ID cannot be null.");
        Objects.requireNonNull(target, "ReactionTarget cannot be null.");
        Objects.requireNonNull(reactionType, "ReactionType cannot be null.");
    }
}
