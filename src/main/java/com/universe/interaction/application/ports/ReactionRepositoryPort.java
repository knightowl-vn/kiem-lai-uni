package com.universe.interaction.application.ports;

import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Output Port defining persistence operations for Content Reactions.
 *
 * <p>Preserves Clean Architecture boundaries: pure domain types only, no framework or JPA types.
 */
public interface ReactionRepositoryPort {

    /**
     * Saves or updates a {@link Reaction} aggregate.
     */
    Reaction save(Reaction reaction);

    /**
     * Finds a reaction by its unique ID.
     */
    Optional<Reaction> findById(UUID reactionId);

    /**
     * Finds an existing reaction for an authenticated user on a specific target.
     */
    Optional<Reaction> findByUserAndTarget(UUID userId, ReactionTarget target);

    /**
     * Finds only the active {@link ReactionType} for an authenticated user on a specific target.
     */
    Optional<ReactionType> findUserReactionType(UUID userId, ReactionTarget target);

    /**
     * Deletes a reaction aggregate.
     */
    void delete(Reaction reaction);

    /**
     * Deletes a reaction for a given user and target if present.
     *
     * @return {@code true} if a reaction was deleted, {@code false} otherwise.
     */
    boolean deleteByUserAndTarget(UUID userId, ReactionTarget target);

    /**
     * Counts reactions for a single target, grouped by {@link ReactionType}.
     * Returns a map containing all enum values with 0L if no reactions exist for a type.
     */
    Map<ReactionType, Long> countReactionsByTargetGroupedByType(ReactionTarget target);

    /**
     * Batch counts reactions for multiple target IDs of a given {@link ReactionTargetType}, grouped by {@link ReactionType}.
     */
    Map<UUID, Map<ReactionType, Long>> countReactionsByTargetIdsGroupedByType(
            ReactionTargetType targetType,
            Collection<UUID> targetIds
    );

    /**
     * Batch finds the active {@link ReactionType} for an authenticated user across multiple target IDs.
     */
    Map<UUID, ReactionType> findUserReactionsForTargetIds(
            UUID userId,
            ReactionTargetType targetType,
            Collection<UUID> targetIds
    );

    /**
     * Counts the total number of reactions across all types for a given target.
     */
    long countTotalReactionsByTarget(ReactionTarget target);
}
