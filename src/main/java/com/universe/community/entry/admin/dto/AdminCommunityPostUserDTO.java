package com.universe.community.entry.admin.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Enriched user representation for Admin Community Post moderation views.
 */
public record AdminCommunityPostUserDTO(
        UUID userId,
        String displayName,
        String avatarUrl,
        String publicHandle,
        boolean resolved
) {
    public AdminCommunityPostUserDTO {
        Objects.requireNonNull(userId, "userId cannot be null.");
    }

    public static AdminCommunityPostUserDTO resolved(
            UUID userId,
            String displayName,
            String avatarUrl,
            String publicHandle
    ) {
        return new AdminCommunityPostUserDTO(userId, displayName, avatarUrl, publicHandle, true);
    }

    public static AdminCommunityPostUserDTO unresolved(UUID userId) {
        return new AdminCommunityPostUserDTO(userId, "Người dùng " + userId.toString().substring(0, 8), null, null, false);
    }
}
