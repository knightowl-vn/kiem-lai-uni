package com.universe.interaction.entry.dto;

/**
 * Request coordinates for anchoring an inline comment to a specific Reader block and text range.
 *
 * <p>Contains exclusively canonical coordinates. Text evidence and context are reconstructed
 * strictly server-side from the current canonical Reader snapshot.
 */
public record InlineTextAnchorRequest(
        long contentVersion,
        String blockKey,
        int startOffset,
        int endOffset
) {
}
