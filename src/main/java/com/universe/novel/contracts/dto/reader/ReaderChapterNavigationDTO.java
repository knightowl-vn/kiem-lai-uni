package com.universe.novel.contracts.dto.reader;

public record ReaderChapterNavigationDTO(
        int chapterNumber,
        String title,
        String slug
) {
}
