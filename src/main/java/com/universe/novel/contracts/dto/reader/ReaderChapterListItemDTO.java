package com.universe.novel.contracts.dto.reader;

import java.util.UUID;

public record ReaderChapterListItemDTO(
        UUID id,
        int chapterNumber,
        String title,
        String slug
) {
}