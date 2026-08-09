package com.universe.wiki.contracts.dto;

import java.util.List;

/**
 * Kết quả phân trang lịch sử chỉnh sửa của một bài Wiki.
 */
public record WikiArticleRevisionPageDTO(
        List<WikiArticleRevisionListItemDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public WikiArticleRevisionPageDTO {
        items =
                items == null
                        ? List.of()
                        : List.copyOf(items);
    }
}