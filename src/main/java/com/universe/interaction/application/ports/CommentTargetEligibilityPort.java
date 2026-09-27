package com.universe.interaction.application.ports;

import com.universe.interaction.domain.CommentTarget;

/**
 * Output port expressing whether a generic {@link CommentTarget} is currently eligible for comments.
 *
 * <p>Implemented by target-owning bounded contexts (Novel, Wiki) to verify publication,
 * visibility, and interaction eligibility without coupling the Interaction module to
 * domain-specific rules of other modules.
 */
public interface CommentTargetEligibilityPort {

    /**
     * Determines whether the given target is currently eligible for new comments or replies.
     *
     * @param target the comment target (cannot be null)
     * @return true if eligible, false otherwise
     */
    boolean isEligible(CommentTarget target);
}
