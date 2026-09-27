package com.universe.interaction.application.ports;

import com.universe.interaction.domain.reaction.ReactionTarget;

/**
 * Application port to evaluate whether a {@link ReactionTarget} is eligible to receive reactions.
 */
public interface ReactionTargetEligibilityPort {

    /**
     * Evaluates if the given target is currently published/active and eligible for emotional reactions.
     *
     * @param target the reaction target
     * @return {@code true} if eligible, {@code false} otherwise
     */
    boolean isEligible(ReactionTarget target);
}
