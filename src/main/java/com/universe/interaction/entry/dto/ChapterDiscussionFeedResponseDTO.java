package com.universe.interaction.entry.dto;

import java.util.List;

/**
 * Immutable response envelope for paginated chapter discussion feed items.
 *
 * <p>Uses zero-based slice pagination without issuing COUNT(*) queries.
 */
public record ChapterDiscussionFeedResponseDTO(
        List<ChapterDiscussionFeedItemDTO> items,
        int page,
        int size,
        boolean hasNext
) {
    public ChapterDiscussionFeedResponseDTO {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
