package com.universe.media.contracts.dto;

import java.util.List;

/**
 * Paginated container for {@link MediaUploadSessionResponseDTO}.
 */
public record MediaUploadSessionPageDTO(
        List<MediaUploadSessionResponseDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public MediaUploadSessionPageDTO {
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

    public static MediaUploadSessionPageDTO empty(int page, int size) {
        return new MediaUploadSessionPageDTO(
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
