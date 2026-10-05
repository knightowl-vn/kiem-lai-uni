package com.universe.interaction.entry.community.dto;

import java.util.List;

/**
 * Immutable response envelope for paginated Community post discussion feed items.
 *
 * <p>Contains:
 * <ul>
 *   <li>roots: paginated list of root discussion items with reply counts and resolved authors;</li>
 *   <li>commentCount: total count of active comments for the post;</li>
 *   <li>page: zero-based page index;</li>
 *   <li>size: page size;</li>
 *   <li>hasNext: true if more root discussion items exist beyond this page.</li>
 * </ul>
 */
public record CommunityDiscussionFeedResponseDTO(
        List<CommunityRootCommentDTO> roots,
        long commentCount,
        int page,
        int size,
        boolean hasNext
) {
    public CommunityDiscussionFeedResponseDTO {
        roots = roots == null ? List.of() : List.copyOf(roots);
    }
}
