package com.universe.community.contracts.dto;

import java.util.Objects;

/**
 * Public read projection DTO representing an author's public profile and their initial post feed.
 */
public record CommunityAuthorProfileDTO(
        String publicHandle,
        String displayName,
        String avatarUrl,
        String bio,
        CommunityNewestFeedResponseDTO posts
) {
    public CommunityAuthorProfileDTO {
        Objects.requireNonNull(publicHandle, "Public handle cannot be null.");
        Objects.requireNonNull(displayName, "Display name cannot be null.");
        Objects.requireNonNull(posts, "Posts cannot be null.");
    }
}
