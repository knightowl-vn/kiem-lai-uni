package com.universe.notification.application.port;

import com.universe.notification.domain.Notification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound repository port for Notification persistence operations.
 */
public interface NotificationRepositoryPort {

    Optional<Notification> findById(UUID id);

    void save(Notification notification);

    long countUnreadByRecipientUserId(UUID recipientUserId);

    boolean markAsRead(UUID id, UUID recipientUserId, Instant now);

    int markAllAsRead(UUID recipientUserId, Instant now);
}
