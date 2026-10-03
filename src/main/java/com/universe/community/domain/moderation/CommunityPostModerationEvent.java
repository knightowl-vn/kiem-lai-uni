package com.universe.community.domain.moderation;

import com.universe.community.domain.CommunityPostStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain event representing a moderation action applied to a Community Post.
 */
public record CommunityPostModerationEvent(
        UUID id,
        UUID postId,
        CommunityPostModerationAction action,
        CommunityPostStatus fromStatus,
        CommunityPostStatus toStatus,
        UUID moderatorUserId,
        String reason,
        Instant createdAt
) {
    public CommunityPostModerationEvent {
        Objects.requireNonNull(id, "Event ID cannot be null.");
        Objects.requireNonNull(postId, "Post ID cannot be null.");
        Objects.requireNonNull(action, "Moderation action cannot be null.");
        Objects.requireNonNull(fromStatus, "fromStatus cannot be null.");
        Objects.requireNonNull(toStatus, "toStatus cannot be null.");
        Objects.requireNonNull(moderatorUserId, "Moderator user ID cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt cannot be null.");
    }
}
