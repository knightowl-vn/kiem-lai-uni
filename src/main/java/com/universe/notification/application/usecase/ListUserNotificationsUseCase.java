package com.universe.notification.application.usecase;

import com.universe.notification.application.model.NotificationFilter;
import com.universe.notification.application.port.NotificationQueryPort;
import com.universe.notification.contracts.dto.NotificationPageDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Use case retrieving a paginated feed of notifications for an authenticated user.
 */
@Service
public class ListUserNotificationsUseCase {

    private final NotificationQueryPort notificationQueryPort;

    public ListUserNotificationsUseCase(NotificationQueryPort notificationQueryPort) {
        this.notificationQueryPort = Objects.requireNonNull(
                notificationQueryPort,
                "NotificationQueryPort cannot be null."
        );
    }

    @Transactional(readOnly = true)
    public NotificationPageDTO execute(
            UUID recipientUserId,
            NotificationFilter filter,
            int page,
            int size
    ) {
        Objects.requireNonNull(recipientUserId, "Recipient user ID cannot be null.");
        NotificationFilter safeFilter = filter != null ? filter : NotificationFilter.ALL;
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        return notificationQueryPort.findByRecipientUserId(recipientUserId, safeFilter, safePage, safeSize);
    }
}
