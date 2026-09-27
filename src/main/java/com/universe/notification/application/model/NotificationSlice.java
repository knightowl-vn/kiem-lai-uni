package com.universe.notification.application.model;

import com.universe.notification.domain.Notification;

import java.util.List;

/**
 * Paginated slice of domain {@link Notification} entities.
 */
public record NotificationSlice(
        List<Notification> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean hasNext
) {
    public NotificationSlice {
        items = items != null ? List.copyOf(items) : List.of();
    }
}
