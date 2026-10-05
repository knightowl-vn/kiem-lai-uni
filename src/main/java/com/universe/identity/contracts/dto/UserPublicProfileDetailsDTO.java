package com.universe.identity.contracts.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Public profile detail data representation for full public author profiles.
 *
 * <p>Exposes non-sensitive public identity information:
 * <ul>
 *   <li>{@code userId}: User UUID</li>
 *   <li>{@code displayName}: Clean public display name</li>
 *   <li>{@code avatarUrl}: Public avatar URL (nullable)</li>
 *   <li>{@code publicHandle}: Immutable public handle</li>
 *   <li>{@code bio}: Public biography text (nullable)</li>
 * </ul>
 */
public record UserPublicProfileDetailsDTO(
        UUID userId,
        String displayName,
        String avatarUrl,
        String publicHandle,
        String bio
) {
    public UserPublicProfileDetailsDTO {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(displayName, "displayName cannot be null");
        if (publicHandle == null || publicHandle.isBlank()) {
            throw new IllegalArgumentException("publicHandle cannot be null or blank");
        }
    }
}
