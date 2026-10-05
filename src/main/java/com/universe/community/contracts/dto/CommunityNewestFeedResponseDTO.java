package com.universe.community.contracts.dto;

import java.util.List;

/**
 * Response envelope for paginated Community NEWEST feed items using keyset cursor pagination.
 */
public record CommunityNewestFeedResponseDTO(
        List<CommunityPostFeedItemDTO> items,
        String nextCursor,
        int size,
        boolean hasNext
) {
    public CommunityNewestFeedResponseDTO {
        items = items == null ? List.of() : List.copyOf(items);
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be greater than zero: " + size);
        }
    }
}
