package com.universe.screen.application.dto;

import java.util.List;

/**
 * Paginated response container for {@link RecoverableVideoUploadView}.
 */
public record RecoverableVideoUploadPageDTO(
        List<RecoverableVideoUploadView> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public RecoverableVideoUploadPageDTO {
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

    public static RecoverableVideoUploadPageDTO empty(int page, int size) {
        return new RecoverableVideoUploadPageDTO(
                List.of(),
                Math.max(0, page),
                size > 0 ? size : 20,
                0L,
                0,
                true,
                true
        );
    }
}
