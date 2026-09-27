package com.universe.interaction.entry.dto;

/**
 * Request payload for editing an existing comment's body.
 */
public record EditCommentRequest(
        String body
) {
}
