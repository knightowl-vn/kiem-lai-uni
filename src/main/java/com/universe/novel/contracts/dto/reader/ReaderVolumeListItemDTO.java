package com.universe.novel.contracts.dto.reader;

import java.util.UUID;

public record ReaderVolumeListItemDTO(
        UUID id,
        String title,
        String slug,
        int sortOrder,
        long publishedChapterCount
) {
}