package com.universe.wiki.contracts.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Dữ liệu rút gọn của một revision trên màn hình lịch sử.
 *
 * Không trả summary và content vì các trường này có thể lớn.
 */
public record WikiArticleRevisionListItemDTO(
        UUID id,
        UUID articleId,
        long revisionNumber,
        String status,
        String changeType,
        String editSummary,
        UUID editedBy,
        Instant createdAt
) {
}