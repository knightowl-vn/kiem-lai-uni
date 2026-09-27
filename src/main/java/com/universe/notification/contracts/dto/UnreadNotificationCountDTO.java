package com.universe.notification.contracts.dto;

/**
 * Lightweight DTO carrying the unread notification badge count.
 */
public record UnreadNotificationCountDTO(
        long unreadCount
) {
}
