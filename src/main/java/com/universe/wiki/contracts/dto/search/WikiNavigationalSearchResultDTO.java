package com.universe.wiki.contracts.dto.search;

import java.util.List;

/**
 * Kết quả tìm kiếm điều hướng Wiki.
 */
public record WikiNavigationalSearchResultDTO(
        String query,
        List<WikiNavigationalSearchItemDTO> items
) {

    public WikiNavigationalSearchResultDTO {
        items = items != null ? List.copyOf(items) : List.of();
    }
}
