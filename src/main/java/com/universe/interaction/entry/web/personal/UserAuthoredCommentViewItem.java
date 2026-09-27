package com.universe.interaction.entry.web.personal;

import com.universe.interaction.domain.CommentTargetType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * View item representing an authored comment ready for presentation in the personal comments UI.
 */
public record UserAuthoredCommentViewItem(
        UUID commentId,
        CommentTargetType targetType,
        UUID targetId,
        String body,
        Instant createdAt,
        Instant updatedAt,
        boolean isReply,
        String contextLabel,
        String typeLabel,
        boolean targetAvailable,
        String targetTitle,
        String targetUnavailableLabel,
        String contextUrl,
        UUID threadRootCommentId
) {
    public UserAuthoredCommentViewItem {
        Objects.requireNonNull(commentId, "commentId cannot be null.");
        Objects.requireNonNull(targetType, "targetType cannot be null.");
        Objects.requireNonNull(targetId, "targetId cannot be null.");
        Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        Objects.requireNonNull(contextLabel, "contextLabel cannot be null.");
        Objects.requireNonNull(typeLabel, "typeLabel cannot be null.");
        Objects.requireNonNull(targetUnavailableLabel, "targetUnavailableLabel cannot be null.");
    }

    public boolean isRoot() {
        return !isReply;
    }
}
