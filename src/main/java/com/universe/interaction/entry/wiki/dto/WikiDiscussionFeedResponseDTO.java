package com.universe.interaction.entry.wiki.dto;

import com.universe.interaction.entry.dto.CommentThreadResponseDTO;

import java.util.List;

/**
 * Immutable response envelope for paginated Wiki article discussion feed items.
 *
 * <p>Contains:
 * <ul>
 *   <li>threads: paginated list of root discussion threads with visible flat replies and resolved authors;</li>
 *   <li>threadCount: total count of visible root discussion threads on the article;</li>
 *   <li>commentCount: total count of visible active comments (roots + active replies) on the article;</li>
 *   <li>page: zero-based page index;</li>
 *   <li>size: page size;</li>
 *   <li>hasNext: true if more root discussion threads exist beyond this page.</li>
 * </ul>
 */
public record WikiDiscussionFeedResponseDTO(
        List<CommentThreadResponseDTO> threads,
        int threadCount,
        int commentCount,
        int page,
        int size,
        boolean hasNext
) {
    public WikiDiscussionFeedResponseDTO {
        threads = threads == null ? List.of() : List.copyOf(threads);
    }
}
