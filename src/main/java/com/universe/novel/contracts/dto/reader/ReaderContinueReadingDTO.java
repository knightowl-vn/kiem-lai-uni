package com.universe.novel.contracts.dto.reader;

import java.util.Objects;
import java.util.UUID;

public record ReaderContinueReadingDTO(
        UUID chapterId,
        int chapterNumber,
        String title,
        String slug,
        int highestReachedChapterNumber
) {

    public ReaderContinueReadingDTO {
        Objects.requireNonNull(
                chapterId,
                "Chapter ID không được để trống."
        );
        Objects.requireNonNull(
                title,
                "Tiêu đề chương không được để trống."
        );
        Objects.requireNonNull(
                slug,
                "Slug chương không được để trống."
        );
    }
}
