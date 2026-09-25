package com.universe.wiki.entry.admin.dto;

import com.universe.wiki.domain.contribution.WikiContributionSource;
import com.universe.wiki.domain.contribution.WikiContributionSourceType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DTO đại diện cho một nguồn tham khảo trong trang chi tiết đóng góp quản trị.
 */
public record AdminWikiContributionSourceDTO(
        UUID id,
        int sourceOrder,
        WikiContributionSourceType sourceType,
        String url,
        Instant createdAt
) {
    public static AdminWikiContributionSourceDTO from(WikiContributionSource source) {
        Objects.requireNonNull(source, "source cannot be null");
        return new AdminWikiContributionSourceDTO(
                source.getId(),
                source.getSourceOrder(),
                source.getSourceType(),
                source.getUrl(),
                source.getCreatedAt()
        );
    }
}
