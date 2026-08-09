package com.universe.wiki.contracts.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Dữ liệu bài Wiki công khai dành cho người đọc.
 */
public record PublishedWikiArticleDTO(
        UUID id,
        String title,
        String slug,
        String articleType,
        String summary,
        String content,
        Instant publishedAt,
        Instant updatedAt
) {
}