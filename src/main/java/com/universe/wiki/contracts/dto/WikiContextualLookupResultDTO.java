package com.universe.wiki.contracts.dto;

import java.util.List;

/**
 * DTO kết quả tra cứu Wiki theo ngữ cảnh từ văn bản được chọn.
 */
public record WikiContextualLookupResultDTO(
        String query,
        boolean hasExactMatch,
        List<WikiContextualLookupItemDTO> items
) {
}
