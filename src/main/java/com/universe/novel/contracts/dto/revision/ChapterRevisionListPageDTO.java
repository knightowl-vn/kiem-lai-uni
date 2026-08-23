package com.universe.novel.contracts.dto.revision;

import java.util.List;

public record ChapterRevisionListPageDTO(
        List<ChapterRevisionListItemDTO> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasPrevious,
        boolean hasNext
) {
}
