package com.universe.identity.contracts.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Public profile data representation for public display (e.g. comments, reviews).
 *
 * <p>Exposes only non-sensitive public identity information:
 * <ul>
 *   <li>{@code userId}: User UUID</li>
 *   <li>{@code displayName}: Clean public display name</li>
 *   <li>{@code avatarUrl}: Public avatar URL</li>
 *   <li>{@code publicHandle}: Immutable public handle</li>
 * </ul>
 */
public record UserPublicProfileDTO(
        UUID userId,
        String displayName,
        String avatarUrl,
        String publicHandle
) {
    public UserPublicProfileDTO {
        Objects.requireNonNull(userId, "userId cannot be null");
        if (publicHandle == null || publicHandle.isBlank()) {
            throw new IllegalArgumentException("publicHandle cannot be null or blank");
        }
    }
}
