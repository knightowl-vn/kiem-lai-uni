package com.universe.interaction.application.query;

import java.util.Objects;
import java.util.UUID;

/**
 * Read DTO containing reaction count and comment count for a Community Post.
 */
public record CommunityPostEngagementCountsDTO(
        UUID postId,
        long reactionCount,
        long commentCount
) {
    public CommunityPostEngagementCountsDTO {
        Objects.requireNonNull(postId, "Post ID cannot be null.");
    }
}
