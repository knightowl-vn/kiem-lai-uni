package com.universe.wiki.contracts.dto.saved;

import java.util.List;

/**
 * Kết quả phân trang của danh sách bài viết Wiki đã lưu của người dùng.
 * Đảm bảo thuần Java, không phụ thuộc vào Spring Data types.
 */
public record SavedWikiArticlePageDTO(
        List<SavedWikiArticleItemDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public SavedWikiArticlePageDTO {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
