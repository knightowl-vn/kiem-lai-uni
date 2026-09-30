package com.universe.community.application.cursor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Value record representing a keyset cursor point in the NEWEST feed sequence.
 */
public record CommunityPostKeysetCursor(
        Instant createdAt,
        UUID postId
) {
    public CommunityPostKeysetCursor {
        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null in keyset cursor.");
        Objects.requireNonNull(postId, "Post ID cannot be null in keyset cursor.");
    }
}
