package com.universe.wiki.contracts.dto;

import java.util.List;

/**
 * Kết quả phân trang danh sách bài Wiki công khai.
 */
public record PublishedWikiArticlePageDTO(
        List<PublishedWikiArticleListItemDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public PublishedWikiArticlePageDTO {
        items =
                items == null
                        ? List.of()
                        : List.copyOf(items);
    }
}