package com.universe.wiki.contracts.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Dữ liệu rút gọn của bài Wiki công khai.
 *
 * Dùng cho card/dòng danh sách, không chứa content đầy đủ.
 */
public record PublishedWikiArticleListItemDTO(
        UUID id,
        String title,
        String slug,
        String articleType,
        String summary,
        Instant publishedAt,
        Instant updatedAt
) {
}