package com.universe.interaction.entry.dto;

/**
 * Request coordinates for anchoring an inline comment to a specific Reader block.
 *
 * <p>Contains exclusively canonical block coordinates. Text evidence is reconstructed
 * strictly server-side from the current canonical Reader snapshot.
 */
public record InlineBlockAnchorRequest(
        long contentVersion,
        String blockKey
) {
}
