package com.universe.novel.application.chapter.reference;

import com.universe.novel.domain.reference.ChapterWikiReferenceScope;

import java.time.Instant;
import java.util.UUID;

/**
 * Data-only DTO đại diện cho một liên kết Wiki của Chapter ở tầng Application,
 * bao gồm metadata của bài viết Wiki đã xuất bản (nếu khả dụng).
 */
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
        PublishedWikiArticleSummary wikiArticle,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
