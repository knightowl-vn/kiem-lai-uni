package com.universe.interaction.entry.dto;

/**
 * Request payload for creating a root comment or a reply.
 *
 * <p>Contains exclusively the comment body. Author identity and target are derived strictly
 * from the authenticated session and route parameters.
 */
public record CreateCommentRequest(
        String body
) {
}
