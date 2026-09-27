package com.universe.notification.contracts.dto;

import java.util.List;

/**
 * Paginated slice DTO for notification feed responses.
 */
public record NotificationPageDTO(
        List<NotificationDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean hasNext
) {
    public static NotificationPageDTO empty(int page, int size) {
        return new NotificationPageDTO(
                List.of(),
                page,
                size,
                0L,
                0,
                true,
                true,
                false
        );
    }
}
