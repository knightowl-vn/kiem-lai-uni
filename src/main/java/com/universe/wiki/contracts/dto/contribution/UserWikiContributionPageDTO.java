package com.universe.wiki.contracts.dto.contribution;

import java.util.List;

/**
 * Kết quả phân trang của danh sách đóng góp Wiki cá nhân của người dùng.
 * Thuần Java record, không phụ thuộc vào Spring Data types.
 */
public record UserWikiContributionPageDTO(
        List<UserWikiContributionItemDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public UserWikiContributionPageDTO {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
