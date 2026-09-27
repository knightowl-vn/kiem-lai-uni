package com.universe.wiki.entry.admin.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Contributor identity presentation representation for the admin contribution queue.
 */
public record AdminWikiContributionContributorDTO(
        UUID userId,
        String displayName,
        String avatarUrl,
        boolean resolved
) {
    public AdminWikiContributionContributorDTO {
        Objects.requireNonNull(userId, "userId cannot be null");
    }

    public static AdminWikiContributionContributorDTO unresolved(UUID userId) {
        return new AdminWikiContributionContributorDTO(userId, null, null, false);
    }
}
