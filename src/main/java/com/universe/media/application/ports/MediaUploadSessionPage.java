package com.universe.media.application.ports;

import com.universe.media.domain.MediaUploadSession;

import java.util.List;

/**
 * Paginated application port container for {@link MediaUploadSession}.
 */
public record MediaUploadSessionPage(
        List<MediaUploadSession> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public MediaUploadSessionPage {
        items = items != null ? List.copyOf(items) : List.of();
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be positive: " + size);
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("Total elements cannot be negative: " + totalElements);
        }
        if (totalPages < 0) {
            throw new IllegalArgumentException("Total pages cannot be negative: " + totalPages);
        }
    }

    public boolean isFirst() {
        return page == 0;
    }

    public boolean isLast() {
        return totalPages == 0 || page >= totalPages - 1;
    }
}
