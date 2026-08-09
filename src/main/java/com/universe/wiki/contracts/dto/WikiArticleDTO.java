package com.universe.wiki.contracts.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Dữ liệu công khai của một Wiki Article.
 *
 * DTO không để lộ enum hoặc Aggregate nội bộ.
 */
public record WikiArticleDTO(
        UUID id,
        String title,
        String slug,
        String articleType,
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
        long aggregateVersion
) {
}