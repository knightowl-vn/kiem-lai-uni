package com.universe.community.application.port.out;

import java.util.Objects;
import java.util.UUID;

/**
 * Value record representing lightweight author public identity summary for feed post presentation.
 */
public record CommunityAuthorProfileSummary(
        UUID userId,
        String publicHandle,
        String displayName,
        String avatarUrl
) {
    public CommunityAuthorProfileSummary {
        Objects.requireNonNull(userId, "User ID cannot be null.");
        Objects.requireNonNull(publicHandle, "Public handle cannot be null.");
        Objects.requireNonNull(displayName, "Display name cannot be null.");
    }
}
