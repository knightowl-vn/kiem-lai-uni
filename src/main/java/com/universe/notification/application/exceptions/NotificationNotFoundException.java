package com.universe.notification.application.exceptions;

import java.util.UUID;

/**
 * Thrown when a requested notification cannot be located or does not belong
 * to the authenticated recipient user.
 */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(UUID id) {
        super("Notification not found: " + id);
    }

    public NotificationNotFoundException(String message) {
        super(message);
    }
}
