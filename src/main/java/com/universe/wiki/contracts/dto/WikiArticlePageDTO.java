package com.universe.wiki.contracts.dto;

import java.util.List;

/**
 * Kết quả phân trang của danh sách bài Wiki.
 */
public record WikiArticlePageDTO(
        List<WikiArticleListItemDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public WikiArticlePageDTO {
        items =
                items == null
                        ? List.of()
                        : List.copyOf(items);
    }
}