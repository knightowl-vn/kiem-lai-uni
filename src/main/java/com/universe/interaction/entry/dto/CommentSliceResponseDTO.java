package com.universe.interaction.entry.dto;

import java.util.List;

/**
 * Slice-based response container for root comments feed.
 *
 * <p>Preserves slice-pagination semantics without total element/page counts.
 */
public record CommentSliceResponseDTO(
        List<CommentReadDTO> items,
        int page,
        int size,
        boolean hasNext
) {
    public CommentSliceResponseDTO {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
