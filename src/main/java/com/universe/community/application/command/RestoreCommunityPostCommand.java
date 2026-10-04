package com.universe.community.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Command for an administrator to restore a hidden Community post to published.
 */
public record RestoreCommunityPostCommand(
        UUID postId,
        UUID moderatorUserId,
        String reason
) {
    public RestoreCommunityPostCommand {
        Objects.requireNonNull(postId, "Post ID cannot be null.");
        Objects.requireNonNull(moderatorUserId, "Moderator user ID cannot be null.");
        reason = (reason != null && !reason.isBlank()) ? reason.trim() : null;
    }
}
