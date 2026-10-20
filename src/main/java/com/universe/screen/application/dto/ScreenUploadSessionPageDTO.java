package com.universe.screen.application.dto;

import java.util.List;

/**
 * Screen-owned paginated container for {@link ScreenUploadSessionInfoDTO}.
 */
public record ScreenUploadSessionPageDTO(
        List<ScreenUploadSessionInfoDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public ScreenUploadSessionPageDTO {
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

    public static ScreenUploadSessionPageDTO empty(int page, int size) {
        return new ScreenUploadSessionPageDTO(
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
