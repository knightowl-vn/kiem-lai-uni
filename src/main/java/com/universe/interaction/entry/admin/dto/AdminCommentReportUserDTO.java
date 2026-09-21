package com.universe.interaction.entry.admin.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable user projection for the Admin comment report queue.
 *
 * <p>Represents either the reporter or the comment author. If the user
 * could not be resolved from Identity, {@code resolved} is {@code false}
 * and display metadata fields are {@code null}.
 *
 * @param userId unique user identifier (never null)
 * @param displayName public display name (nullable if unresolved)
 * @param avatarUrl public avatar URL (nullable)
 * @param resolved whether identity profile was successfully resolved
 */
public record AdminCommentReportUserDTO(
        UUID userId,
        String displayName,
        String avatarUrl,
        boolean resolved
) {
    public AdminCommentReportUserDTO {
        Objects.requireNonNull(userId, "userId cannot be null");
    }

    public static AdminCommentReportUserDTO resolved(UUID userId, String displayName, String avatarUrl) {
        return new AdminCommentReportUserDTO(userId, displayName, avatarUrl, true);
    }

    public static AdminCommentReportUserDTO unresolved(UUID userId) {
        return new AdminCommentReportUserDTO(userId, null, null, false);
    }
}
