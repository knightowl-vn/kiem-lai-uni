package com.universe.novel.application.chapter.reference;

import java.util.List;
import java.util.UUID;

public record ChapterWikiReferenceListPageDTO(
        UUID chapterId,
        String chapterTitle,
        int chapterNumber,
        long currentContentVersion,
        List<ChapterWikiReferenceItemDTO> references,
        int totalCount,
        int activeCount,
        int staleCount
) {
}
