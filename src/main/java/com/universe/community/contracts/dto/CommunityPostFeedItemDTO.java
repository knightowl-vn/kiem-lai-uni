package com.universe.community.contracts.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Public read projection DTO representing an item in a Community feed with public author presentation metadata.
 */
public record CommunityPostFeedItemDTO(
        UUID id,
        UUID authorUserId,
        String authorDisplayName,
        String authorPublicHandle,
        String authorAvatarUrl,
        String caption,
        UUID imageMediaAssetId,
        String imageUrl,
        int contentVersion,
        long reactionCount,
        long commentCount,
        long engagementScore,
        Instant createdAt,
        Instant updatedAt,
        String currentUserReaction
) {
    public CommunityPostFeedItemDTO {
        Objects.requireNonNull(id, "Post ID cannot be null.");
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        Objects.requireNonNull(caption, "Caption cannot be null.");
        Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
        Objects.requireNonNull(updatedAt, "UpdatedAt timestamp cannot be null.");
        if (reactionCount < 0) {
            throw new IllegalArgumentException("Reaction count cannot be negative: " + reactionCount);
        }
        if (commentCount < 0) {
            throw new IllegalArgumentException("Comment count cannot be negative: " + commentCount);
        }
        if (engagementScore < 0) {
            throw new IllegalArgumentException("Engagement score cannot be negative: " + engagementScore);
        }
    }

    public CommunityPostFeedItemDTO(
            UUID id,
            UUID authorUserId,
            String authorDisplayName,
            String authorPublicHandle,
            String authorAvatarUrl,
            String caption,
            UUID imageMediaAssetId,
            String imageUrl,
            int contentVersion,
            long reactionCount,
            long commentCount,
            long engagementScore,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, authorUserId, authorDisplayName, authorPublicHandle, authorAvatarUrl, caption,
                imageMediaAssetId, imageUrl, contentVersion, reactionCount, commentCount, engagementScore,
                createdAt, updatedAt, null);
    }
}
