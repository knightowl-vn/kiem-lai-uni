package com.universe.notification.application.usecase;

import com.universe.notification.application.exceptions.NotificationNotFoundException;
import com.universe.notification.application.port.NotificationRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case transitioning a single notification to READ state for its recipient owner.
 */
@Service
public class MarkNotificationReadUseCase {

    private final NotificationRepositoryPort notificationRepositoryPort;

    public MarkNotificationReadUseCase(NotificationRepositoryPort notificationRepositoryPort) {
        this.notificationRepositoryPort = Objects.requireNonNull(
                notificationRepositoryPort,
                "NotificationRepositoryPort cannot be null."
        );
    }

    @Transactional
    public void execute(UUID notificationId, UUID recipientUserId, Instant now) {
        Objects.requireNonNull(notificationId, "Notification ID cannot be null.");
        Objects.requireNonNull(recipientUserId, "Recipient user ID cannot be null.");
        Instant timestamp = now != null ? now : Instant.now();

        boolean updated = notificationRepositoryPort.markAsRead(notificationId, recipientUserId, timestamp);
        if (!updated) {
            // Verify ownership and existence:
            // If the notification does not exist or belongs to another user, 404 must be thrown.
            var existing = notificationRepositoryPort.findById(notificationId);
            if (existing.isEmpty() || !existing.get().getRecipientUserId().equals(recipientUserId)) {
                throw new NotificationNotFoundException(notificationId);
            }
            // If it exists and is owned by caller, it is already marked read -> idempotent success
        }
    }
}
