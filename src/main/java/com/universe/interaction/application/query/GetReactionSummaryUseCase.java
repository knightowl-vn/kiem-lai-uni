package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.ReactionTargetNotEligibleException;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.application.ports.ReactionTargetEligibilityPort;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case to retrieve the aggregate emotional reaction summary for a target,
 * including individual reaction counts, total reaction count, and the authenticated user's reaction.
 */
@Service
@Transactional(readOnly = true)
public class GetReactionSummaryUseCase {

    private final ReactionRepositoryPort reactionRepositoryPort;
    private final ReactionTargetEligibilityPort eligibilityPort;

    public GetReactionSummaryUseCase(
            ReactionRepositoryPort reactionRepositoryPort,
            ReactionTargetEligibilityPort eligibilityPort
    ) {
        this.reactionRepositoryPort = Objects.requireNonNull(
                reactionRepositoryPort,
                "ReactionRepositoryPort cannot be null."
        );
        this.eligibilityPort = Objects.requireNonNull(
                eligibilityPort,
                "ReactionTargetEligibilityPort cannot be null."
        );
    }

    public ReactionSummary execute(ReactionTarget target, UUID userId) {
        Objects.requireNonNull(target, "ReactionTarget cannot be null.");

        if (!eligibilityPort.isEligible(target)) {
            throw new ReactionTargetNotEligibleException(target);
        }

        Map<ReactionType, Long> counts = reactionRepositoryPort.countReactionsByTargetGroupedByType(target);
        ReactionType currentUserReaction = null;
        if (userId != null) {
            currentUserReaction = reactionRepositoryPort.findUserReactionType(userId, target).orElse(null);
        }

        return ReactionSummary.of(target, counts, currentUserReaction);
    }

    public ReactionSummary execute(ReactionTarget target) {
        return execute(target, null);
    }
}
