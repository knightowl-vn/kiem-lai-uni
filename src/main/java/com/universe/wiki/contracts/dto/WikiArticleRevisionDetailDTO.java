package com.universe.wiki.contracts.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot đầy đủ của một revision WikiArticle.
 */
public record WikiArticleRevisionDetailDTO(
        UUID id,
        UUID articleId,
        long revisionNumber,
        String title,
        String slug,
        String articleType,
        String summary,
        String content,
        String status,
        String changeType,
        String editSummary,
        UUID editedBy,
        Instant createdAt
) {
}