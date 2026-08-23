package com.universe.novel.contracts.dto.reader;

import java.util.List;
import java.util.UUID;

public record ReaderChapterDetailDTO(
        UUID id,
        int chapterNumber,
        String title,
        String slug,
        String contentHtml,
        ReaderVolumeSummaryDTO volume,
        ReaderChapterNavigationDTO previousChapter,
        ReaderChapterNavigationDTO nextChapter,
        List<ReaderChapterTocItemDTO> tableOfContents
) {
}
