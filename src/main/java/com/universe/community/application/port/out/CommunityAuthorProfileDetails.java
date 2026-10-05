package com.universe.community.application.port.out;

import java.util.Objects;
import java.util.UUID;

/**
 * Value record representing author identity details resolved internally from Identity context.
 */
public record CommunityAuthorProfileDetails(
        UUID userId,
        String publicHandle,
        String displayName,
        String avatarUrl,
        String bio
) {
    public CommunityAuthorProfileDetails {
        Objects.requireNonNull(userId, "User ID cannot be null.");
        Objects.requireNonNull(publicHandle, "Public handle cannot be null.");
        Objects.requireNonNull(displayName, "Display name cannot be null.");
    }
}
