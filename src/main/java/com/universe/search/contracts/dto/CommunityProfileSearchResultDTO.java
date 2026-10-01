package com.universe.search.contracts.dto;

import java.util.List;
import java.util.Objects;

/**
 * Grouped result DTO for Community public author profile search.
 */
public record CommunityProfileSearchResultDTO(
        String query,
        List<CommunityProfileSearchItemDTO> items
) {
    public CommunityProfileSearchResultDTO {
        Objects.requireNonNull(query, "query must not be null");
        items = items != null ? List.copyOf(items) : List.of();
    }
}
