package com.universe.interaction.entry.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable public read representation of a comment author.
 *
 * <p>Exposes only safe public author presentation details:
 * <ul>
 *   <li>{@code userId}: Author's user UUID</li>
 *   <li>{@code displayName}: Safe public display name (defaults to 'Người dùng')</li>
 *   <li>{@code avatarUrl}: Optional public avatar URL</li>
 *   <li>{@code publicHandle}: Optional public handle for profile navigation</li>
 * </ul>
 */
public record CommentAuthorDTO(
        UUID userId,
        String displayName,
        String avatarUrl,
        String publicHandle
) {
    public static final String DEFAULT_DISPLAY_NAME = "Người dùng";

    public CommentAuthorDTO {
        Objects.requireNonNull(userId, "Author userId cannot be null");
        displayName = (displayName != null && !displayName.trim().isEmpty())
                ? displayName.trim()
                : DEFAULT_DISPLAY_NAME;
        publicHandle = (publicHandle != null && !publicHandle.trim().isEmpty())
                ? publicHandle.trim()
                : null;
    }

    public static CommentAuthorDTO fallback(UUID userId) {
        return new CommentAuthorDTO(userId, DEFAULT_DISPLAY_NAME, null, null);
    }
}
