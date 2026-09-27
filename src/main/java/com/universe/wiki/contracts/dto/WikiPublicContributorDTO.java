package com.universe.wiki.contracts.dto;

import java.util.Objects;

/**
 * Public DTO representing an acknowledged contributor on a published Wiki article.
 *
 * <p>Exposes strictly public profile information and the article-local active credit count.
 * Never leaks administrative metadata, moderation audit timestamps, or technical UUIDs.
 */
public record WikiPublicContributorDTO(
        String displayName,
        String avatarUrl,
        long activeCreditCount
) {
    public WikiPublicContributorDTO {
        Objects.requireNonNull(displayName, "displayName cannot be null");
        if (activeCreditCount < 1) {
            throw new IllegalArgumentException("activeCreditCount must be at least 1");
        }
    }
}
