package com.universe.interaction.entry.dto;

import java.util.List;

/**
 * Slice-based public response container for comment revision history.
 *
 * <p>Preserves slice-pagination semantics without total element/page counts.
 */
public record CommentRevisionSliceResponseDTO(
        List<CommentRevisionReadDTO> items,
        int page,
        int size,
        boolean hasNext
) {

    public CommentRevisionSliceResponseDTO {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
