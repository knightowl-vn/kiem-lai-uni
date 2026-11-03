package com.universe.interaction.entry.screen.dto;

import com.universe.interaction.entry.dto.CommentThreadResponseDTO;

import java.util.List;

/**
 * Immutable response envelope for paginated Screen title discussion feed items.
 */
public record ScreenDiscussionFeedResponseDTO(
        List<CommentThreadResponseDTO> threads,
        int threadCount,
        int commentCount,
        int page,
        int size,
        boolean hasNext
) {
    public ScreenDiscussionFeedResponseDTO {
        threads = threads == null ? List.of() : List.copyOf(threads);
    }
}
