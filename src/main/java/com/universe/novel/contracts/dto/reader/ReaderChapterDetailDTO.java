package com.universe.novel.contracts.dto.reader;

import java.util.List;
import java.util.UUID;

public record ReaderChapterDetailDTO(
        UUID id,
        int chapterNumber,
        String title,
        String slug,
        String contentHtml,
        long contentVersion,
        ReaderVolumeSummaryDTO volume,
        ReaderChapterNavigationDTO previousChapter,
        ReaderChapterNavigationDTO nextChapter,
        List<ReaderChapterTocItemDTO> tableOfContents
) {
    public ReaderChapterDetailDTO {
        if (contentVersion < 1L) {
            throw new IllegalArgumentException(
                    "Content version phải lớn hơn hoặc bằng 1."
            );
        }
    }
}
