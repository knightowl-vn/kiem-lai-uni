package com.universe.interaction.entry.dto;

import java.util.UUID;

/**
 * Request payload for creating a root comment or a reply.
 *
 * <p>Contains the comment body and optional episode-origin ScreenEntry ID.
 */
public record CreateCommentRequest(
        String body,
        UUID originScreenEntryId
) {
    public CreateCommentRequest(String body) {
        this(body, null);
    }
}
