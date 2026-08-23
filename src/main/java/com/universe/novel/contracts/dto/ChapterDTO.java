package com.universe.novel.contracts.dto;

import java.time.Instant;
import java.util.UUID;

public record ChapterDTO(
        UUID id,
        UUID volumeId,
        Integer chapterNumber,
        String title,
        String slug,
        String summary,
        String content,
        String status,
        UUID createdBy,
        UUID updatedBy,
        UUID publishedBy,
        UUID archivedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        Instant archivedAt,
        long aggregateVersion,
        long contentVersion
) {
}