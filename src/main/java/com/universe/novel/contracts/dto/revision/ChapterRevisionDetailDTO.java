package com.universe.novel.contracts.dto.revision;

import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;

import java.time.Instant;
import java.util.UUID;

public record ChapterRevisionDetailDTO(
        UUID id,
        UUID chapterId,
        UUID volumeId,
        long revisionNumber,
        long contentVersion,
        int chapterNumber,
        String title,
        Slug slug,
        String summary,
        String content,
        String contentHtml,
        ChapterStatus status,
        ChapterRevisionChangeType changeType,
        String editSummary,
        UUID editedBy,
        Instant createdAt
) {

    public ChapterRevisionDetailDTO withContentHtml(
            String contentHtml
    ) {
        return new ChapterRevisionDetailDTO(
                id,
                chapterId,
                volumeId,
                revisionNumber,
                contentVersion,
                chapterNumber,
                title,
                slug,
                summary,
                content,
                contentHtml,
                status,
                changeType,
                editSummary,
                editedBy,
                createdAt
        );
    }
}
