package com.universe.community.entry.admin.dto;

import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.moderation.CommunityPostModerationAction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Enriched moderation history event for Admin Community Post detail and hidden views.
 */
public record AdminCommunityPostModerationEventDTO(
        UUID id,
        UUID postId,
        CommunityPostModerationAction action,
        CommunityPostStatus fromStatus,
        CommunityPostStatus toStatus,
        AdminCommunityPostUserDTO moderator,
        String reason,
        Instant createdAt
) {
    public AdminCommunityPostModerationEventDTO {
        Objects.requireNonNull(id, "id cannot be null.");
        Objects.requireNonNull(postId, "postId cannot be null.");
        Objects.requireNonNull(action, "action cannot be null.");
        Objects.requireNonNull(fromStatus, "fromStatus cannot be null.");
        Objects.requireNonNull(toStatus, "toStatus cannot be null.");
        Objects.requireNonNull(moderator, "moderator cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
    }
}
