package com.universe.interaction.contracts.dto.authored;

import java.util.List;
import java.util.Objects;

/**
 * Paginated read DTO container for authored comments.
 */
public record AuthoredCommentPageDTO(
        List<AuthoredCommentItemDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public AuthoredCommentPageDTO {
        items = items != null ? List.copyOf(items) : List.of();
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative.");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be positive.");
        }
    }

    public static AuthoredCommentPageDTO empty(int page, int size) {
        return new AuthoredCommentPageDTO(
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
