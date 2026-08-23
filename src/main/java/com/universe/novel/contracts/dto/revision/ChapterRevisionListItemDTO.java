package com.universe.novel.contracts.dto.revision;

import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;

import java.time.Instant;
import java.util.UUID;

public record ChapterRevisionListItemDTO(
        UUID id,
        UUID chapterId,
        long revisionNumber,
        long contentVersion,
        int chapterNumber,
        String title,
        ChapterStatus status,
        ChapterRevisionChangeType changeType,
        String editSummary,
        UUID editedBy,
        Instant createdAt
) {
}
