package com.universe.community.contracts.dto;

import java.util.List;

/**
 * Response envelope for paginated Community FEATURED feed items using page-based live ranking pagination.
 */
public record CommunityFeaturedFeedResponseDTO(
        List<CommunityPostFeedItemDTO> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext
) {
    public CommunityFeaturedFeedResponseDTO {
        items = items == null ? List.of() : List.copyOf(items);
        if (page < 0) {
            throw new IllegalArgumentException("Page cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be greater than zero: " + size);
        }
        if (totalItems < 0) {
            throw new IllegalArgumentException("Total items cannot be negative: " + totalItems);
        }
        if (totalPages < 0) {
            throw new IllegalArgumentException("Total pages cannot be negative: " + totalPages);
        }
    }
}
