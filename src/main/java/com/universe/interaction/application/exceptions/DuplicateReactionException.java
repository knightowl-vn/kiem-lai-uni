package com.universe.interaction.application.exceptions;

import com.universe.interaction.domain.reaction.ReactionTarget;

import java.util.Objects;
import java.util.UUID;

/**
 * Exception thrown when a user attempts to add a duplicate reaction to a target where a reaction already exists.
 */
public class DuplicateReactionException extends RuntimeException {

    private final UUID userId;
    private final ReactionTarget target;

    public DuplicateReactionException(UUID userId, ReactionTarget target, Throwable cause) {
        super("User " + userId + " already has a reaction on target " + target, cause);
        this.userId = Objects.requireNonNull(userId, "User ID cannot be null.");
        this.target = Objects.requireNonNull(target, "ReactionTarget cannot be null.");
    }

    public UUID getUserId() {
        return userId;
    }

    public ReactionTarget getTarget() {
        return target;
    }
}
