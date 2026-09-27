package com.universe.notification.application.model;

/**
 * Filter strategy for querying a user's notification feed.
 */
public enum NotificationFilter {
    ALL,
    UNREAD;

    public static NotificationFilter fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return ALL;
        }
        String normalized = value.trim().toUpperCase();
        if ("UNREAD".equals(normalized)) {
            return UNREAD;
        }
        return ALL;
    }
}
