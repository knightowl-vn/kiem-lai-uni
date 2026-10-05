package com.universe.community.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Command for an administrator to approve a pending review Community post.
 */
public record ApproveCommunityPostCommand(
        UUID postId,
        UUID moderatorUserId,
        String reason
) {
    public ApproveCommunityPostCommand {
        Objects.requireNonNull(postId, "Post ID cannot be null.");
        Objects.requireNonNull(moderatorUserId, "Moderator user ID cannot be null.");
        reason = (reason != null && !reason.isBlank()) ? reason.trim() : null;
    }
}
