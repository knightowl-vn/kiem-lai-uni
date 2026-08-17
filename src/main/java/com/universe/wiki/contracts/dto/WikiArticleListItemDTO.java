package com.universe.wiki.contracts.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Dữ liệu rút gọn của một bài Wiki trên màn hình danh sách.
 *
 * Không trả content vì content có thể rất lớn.
 */
public record WikiArticleListItemDTO(
        UUID id,
        String title,
        String slug,
        String articleType,
        String status,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt,
        long contentVersion
) {
}