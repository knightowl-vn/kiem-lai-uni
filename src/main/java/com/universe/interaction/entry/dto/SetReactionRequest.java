package com.universe.interaction.entry.dto;

import java.util.UUID;

/**
 * Request payload for setting or removing an emotional reaction on a target.
 *
 * <p>Actor identity is derived strictly from the authenticated session.
 * If {@code reactionType} is null or blank, the user's reaction is removed.
 */
public record SetReactionRequest(
        String targetType,
        UUID targetId,
        String reactionType
) {
}
