package com.universe.interaction.entry.dto;

import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.domain.reaction.ReactionType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Public REST response DTO representing the reaction summary for a target.
 */
public record ReactionSummaryResponseDTO(
        String targetType,
        UUID targetId,
        Map<String, Long> counts,
        long totalCount,
        String currentUserReaction
) {
    public static ReactionSummaryResponseDTO from(ReactionSummary summary) {
        Objects.requireNonNull(summary, "ReactionSummary cannot be null.");

        Map<String, Long> counts = new LinkedHashMap<>();
        for (ReactionType type : ReactionType.values()) {
            counts.put(type.name(), summary.counts().getOrDefault(type, 0L));
        }

        return new ReactionSummaryResponseDTO(
                summary.target().type().name(),
                summary.target().targetId(),
                Collections.unmodifiableMap(counts),
                summary.totalCount(),
                summary.currentUserReaction() != null ? summary.currentUserReaction().name() : null
        );
    }
}
