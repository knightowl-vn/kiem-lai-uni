package com.universe.interaction.application.query;

import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Read-model record representing the aggregate reaction state for a target.
 *
 * <p>Invariants enforced:
 * <ul>
 *   <li>{@code target}: Non-null target content reference;</li>
 *   <li>{@code counts}: Immutable map containing exact non-negative counts for all {@link ReactionType} enum values;</li>
 *   <li>Missing enum keys in input maps are normalized to 0L;</li>
 *   <li>Explicit null count values, negative counts, or null keys are rejected as internal invariant violations;</li>
 *   <li>{@code totalCount}: Must strictly equal the sum of all individual normalized reaction counts;</li>
 *   <li>{@code currentUserReaction}: The active reaction of the current viewer (or {@code null} if unreacted or anonymous).</li>
 * </ul>
 */
public record ReactionSummary(
        ReactionTarget target,
        Map<ReactionType, Long> counts,
        long totalCount,
        ReactionType currentUserReaction
) {
    public ReactionSummary {
        Objects.requireNonNull(target, "ReactionTarget cannot be null.");
        Objects.requireNonNull(counts, "Reaction counts map cannot be null.");

        for (ReactionType key : counts.keySet()) {
            if (key == null) {
                throw new IllegalArgumentException("Reaction counts map cannot contain null keys.");
            }
        }

        Map<ReactionType, Long> normalizedCounts = new EnumMap<>(ReactionType.class);
        long calculatedTotal = 0L;

        for (ReactionType type : ReactionType.values()) {
            if (counts.containsKey(type)) {
                Long count = counts.get(type);
                if (count == null) {
                    throw new IllegalArgumentException("Reaction count for " + type + " cannot be null.");
                }
                if (count < 0) {
                    throw new IllegalArgumentException("Reaction count for " + type + " cannot be negative: " + count);
                }
                normalizedCounts.put(type, count);
                calculatedTotal += count;
            } else {
                normalizedCounts.put(type, 0L);
            }
        }

        if (totalCount != calculatedTotal) {
            throw new IllegalArgumentException(
                    "Total count (" + totalCount + ") does not match sum of individual reaction counts (" + calculatedTotal + ")."
            );
        }

        counts = Collections.unmodifiableMap(normalizedCounts);
    }

    public static ReactionSummary of(
            ReactionTarget target,
            Map<ReactionType, Long> counts,
            ReactionType currentUserReaction
    ) {
        Objects.requireNonNull(target, "ReactionTarget cannot be null.");
        Map<ReactionType, Long> safeCounts = (counts != null) ? counts : Map.of();

        long calculatedTotal = 0L;
        for (ReactionType type : ReactionType.values()) {
            if (safeCounts.containsKey(type)) {
                Long count = safeCounts.get(type);
                if (count == null) {
                    throw new IllegalArgumentException("Reaction count for " + type + " cannot be null.");
                }
                if (count < 0) {
                    throw new IllegalArgumentException("Reaction count for " + type + " cannot be negative: " + count);
                }
                calculatedTotal += count;
            }
        }

        return new ReactionSummary(target, safeCounts, calculatedTotal, currentUserReaction);
    }
}
