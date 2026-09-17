package com.universe.interaction.entry.dto;

/**
 * Request payload for creating an inline comment on a Novel chapter with anchored text.
 *
 * <p>Author identity and chapter target are derived strictly from the authenticated session
 * and route parameters.
 */
public record CreateInlineCommentRequest(
        String body,
        InlineTextAnchorRequest anchor
) {
}
