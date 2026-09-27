package com.universe.notification.application.usecase;

import com.universe.notification.application.port.NotificationRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case transitioning all unread notifications to READ state for an authenticated user.
 */
@Service
public class MarkAllNotificationsReadUseCase {

    private final NotificationRepositoryPort notificationRepositoryPort;

    public MarkAllNotificationsReadUseCase(NotificationRepositoryPort notificationRepositoryPort) {
        this.notificationRepositoryPort = Objects.requireNonNull(
                notificationRepositoryPort,
                "NotificationRepositoryPort cannot be null."
        );
    }

    @Transactional
    public int execute(UUID recipientUserId, Instant now) {
        Objects.requireNonNull(recipientUserId, "Recipient user ID cannot be null.");
        Instant timestamp = now != null ? now : Instant.now();
        return notificationRepositoryPort.markAllAsRead(recipientUserId, timestamp);
    }
}
