package com.universe.notification.application.usecase;

import com.universe.notification.application.port.NotificationRepositoryPort;
import com.universe.notification.contracts.dto.UnreadNotificationCountDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Use case retrieving the unread notification badge count for an authenticated user.
 */
@Service
public class GetUnreadNotificationCountUseCase {

    private final NotificationRepositoryPort notificationRepositoryPort;

    public GetUnreadNotificationCountUseCase(NotificationRepositoryPort notificationRepositoryPort) {
        this.notificationRepositoryPort = Objects.requireNonNull(
                notificationRepositoryPort,
                "NotificationRepositoryPort cannot be null."
        );
    }

    @Transactional(readOnly = true)
    public UnreadNotificationCountDTO execute(UUID recipientUserId) {
        Objects.requireNonNull(recipientUserId, "Recipient user ID cannot be null.");
        long count = notificationRepositoryPort.countUnreadByRecipientUserId(recipientUserId);
        return new UnreadNotificationCountDTO(count);
    }
}
