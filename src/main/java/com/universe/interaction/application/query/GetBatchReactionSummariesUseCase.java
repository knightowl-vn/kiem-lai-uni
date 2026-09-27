package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Internal application use case to batch-query aggregated reaction summaries across multiple targets.
 *
 * <p>Intended for internal composition (e.g. comment feed / chapter view query coordinator)
 * where target IDs are pre-validated by the caller. Executes in O(1) database queries (1 query for grouped counts,
 * and 1 query for user reaction state if authenticated).
 */
@Service
@Transactional(readOnly = true)
public class GetBatchReactionSummariesUseCase {

    private final ReactionRepositoryPort reactionRepositoryPort;

    public GetBatchReactionSummariesUseCase(ReactionRepositoryPort reactionRepositoryPort) {
        this.reactionRepositoryPort = Objects.requireNonNull(
                reactionRepositoryPort,
                "ReactionRepositoryPort cannot be null."
        );
    }

    public Map<UUID, ReactionSummary> execute(
            ReactionTargetType targetType,
            Collection<UUID> targetIds,
            UUID userId
    ) {
        Objects.requireNonNull(targetType, "ReactionTargetType cannot be null.");
        if (targetIds == null || targetIds.isEmpty()) {
            return Map.of();
        }

        Set<UUID> uniqueTargetIds = targetIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (uniqueTargetIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Map<ReactionType, Long>> countsByTargetId =
                reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(targetType, uniqueTargetIds);

        Map<UUID, ReactionType> userReactions = (userId != null)
                ? reactionRepositoryPort.findUserReactionsForTargetIds(userId, targetType, uniqueTargetIds)
                : Map.of();

        Map<UUID, ReactionSummary> result = new LinkedHashMap<>();
        for (UUID targetId : uniqueTargetIds) {
            ReactionTarget target = new ReactionTarget(targetType, targetId);
            Map<ReactionType, Long> counts = countsByTargetId.getOrDefault(targetId, Map.of());
            ReactionType userReaction = userReactions.get(targetId);
            result.put(targetId, ReactionSummary.of(target, counts, userReaction));
        }

        return Collections.unmodifiableMap(result);
    }

    public Map<UUID, ReactionSummary> execute(
            ReactionTargetType targetType,
            Collection<UUID> targetIds
    ) {
        return execute(targetType, targetIds, null);
    }
}
