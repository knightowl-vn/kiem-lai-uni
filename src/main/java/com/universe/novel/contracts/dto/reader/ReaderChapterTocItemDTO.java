package com.universe.novel.contracts.dto.reader;

public record ReaderChapterTocItemDTO(
        int chapterNumber,
        String title,
        String slug
) {
}
