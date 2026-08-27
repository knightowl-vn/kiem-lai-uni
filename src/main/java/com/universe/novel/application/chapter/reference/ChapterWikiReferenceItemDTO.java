package com.universe.novel.application.chapter.reference;

import com.universe.novel.domain.reference.ChapterWikiReferenceScope;

import java.time.Instant;
import java.util.UUID;

public record ChapterWikiReferenceItemDTO(
        UUID id,
        UUID chapterId,
        String term,
        String normalizedTerm,
        ChapterWikiReferenceScope referenceScope,
        int occurrenceIndex,
        String contextSnippet,
        Long boundContentVersion,
        long currentChapterContentVersion,
        UUID wikiArticleId,
        ChapterWikiReferenceStatus status,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
