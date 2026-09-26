package com.universe.interaction.application.exceptions;

import com.universe.interaction.domain.reaction.ReactionTarget;

import java.util.Objects;

/**
 * Exception thrown when attempting to react to an ineligible target
 * (e.g. unpublished/missing novel chapter, deleted comment, or unsupported target).
 */
public class ReactionTargetNotEligibleException extends RuntimeException {

    private final ReactionTarget target;

    public ReactionTargetNotEligibleException(ReactionTarget target) {
        super("Reaction target is not eligible for reactions: " + (target != null ? target.type() : "null"));
        this.target = Objects.requireNonNull(target, "ReactionTarget cannot be null.");
    }

    public ReactionTarget getTarget() {
        return target;
    }
}
