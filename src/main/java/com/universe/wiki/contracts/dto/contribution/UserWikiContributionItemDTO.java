package com.universe.wiki.contracts.dto.contribution;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO đại diện cho một đóng góp Wiki của người dùng trong danh sách đóng góp cá nhân.
 */
public record UserWikiContributionItemDTO(
        UUID id,
        UUID articleId,
        String articleTitleSnapshot,
        String articleSlugSnapshot,
        String articleTypeSnapshot,
        String contextType,
        String contributionType,
        String message,
        String status,
        Instant createdAt,
        String resolutionNote,
        Instant resolvedAt
) {
}
