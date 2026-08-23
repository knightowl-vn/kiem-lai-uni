package com.universe.novel.contracts.dto.reader;

public record ReaderNovelOverviewDTO(
        String title,
        String slug,
        String author,
        String description,
        String coverImageUrl,
        String status
) {
}