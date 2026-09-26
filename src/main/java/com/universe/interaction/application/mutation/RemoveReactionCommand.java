package com.universe.interaction.application.mutation;

import com.universe.interaction.domain.reaction.ReactionTarget;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to remove an authenticated user's reaction from a target.
 */
public record RemoveReactionCommand(
        UUID userId,
        ReactionTarget target
) {
    public RemoveReactionCommand {
        Objects.requireNonNull(userId, "User ID cannot be null.");
        Objects.requireNonNull(target, "ReactionTarget cannot be null.");
    }
}
