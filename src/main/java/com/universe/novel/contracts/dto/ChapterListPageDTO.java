package com.universe.novel.contracts.dto;

import java.util.List;

public record ChapterListPageDTO(
        List<ChapterListItemDTO> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasPrevious,
        boolean hasNext
) {
}