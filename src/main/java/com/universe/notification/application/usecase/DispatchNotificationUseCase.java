package com.universe.notification.application.usecase;

import com.universe.notification.contracts.command.NotificationDispatchCommand;
import com.universe.notification.contracts.port.NotificationDispatchPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Use case orchestrating internal dispatch of in-app notifications.
 */
@Service
public class DispatchNotificationUseCase {

    private final NotificationDispatchPort notificationDispatchPort;

    public DispatchNotificationUseCase(NotificationDispatchPort notificationDispatchPort) {
        this.notificationDispatchPort = Objects.requireNonNull(
                notificationDispatchPort,
                "NotificationDispatchPort cannot be null."
        );
    }

    @Transactional
    public void execute(NotificationDispatchCommand command) {
        Objects.requireNonNull(command, "NotificationDispatchCommand cannot be null.");
        notificationDispatchPort.dispatch(command);
    }
}
