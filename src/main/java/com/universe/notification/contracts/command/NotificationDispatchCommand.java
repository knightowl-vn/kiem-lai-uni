package com.universe.notification.contracts.command;

import com.universe.notification.domain.NotificationType;

import java.util.Objects;
import java.util.UUID;

/**
 * Command object used by publisher contexts (Interaction, Wiki, etc.) to dispatch
 * an in-app notification event into the Notification bounded context.
 */
public record NotificationDispatchCommand(
        UUID recipientUserId,
        NotificationType type,
        UUID actorUserId,
        String actorDisplayNameSnapshot,
        String targetType,
        UUID targetId,
        String targetTitleSnapshot,
        UUID commentId,
        UUID threadRootId,
        String detailSnapshot,
        String dedupeKey
) {
    public NotificationDispatchCommand {
        Objects.requireNonNull(recipientUserId, "Recipient user ID cannot be null.");
        Objects.requireNonNull(type, "Notification type cannot be null.");
        if (dedupeKey == null || dedupeKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Dedupe key cannot be blank.");
        }
    }
}
