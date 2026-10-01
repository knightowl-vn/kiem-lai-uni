package com.universe.search.contracts.dto;

import java.util.Objects;

/**
 * Public Search item representation for a public Community author profile result.
 *
 * <p>Exposes only safe public display information:
 * <ul>
 *   <li>{@code displayName}: Author display name</li>
 *   <li>{@code publicHandle}: Immutable canonical handle</li>
 *   <li>{@code avatarUrl}: Author avatar image URL (nullable)</li>
 *   <li>{@code profileUrl}: Destination route ({@code /community/@{publicHandle}})</li>
 * </ul>
 */
public record CommunityProfileSearchItemDTO(
        String displayName,
        String publicHandle,
        String avatarUrl,
        String profileUrl
) {
    public CommunityProfileSearchItemDTO {
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(publicHandle, "publicHandle must not be null");
        Objects.requireNonNull(profileUrl, "profileUrl must not be null");
    }
}
