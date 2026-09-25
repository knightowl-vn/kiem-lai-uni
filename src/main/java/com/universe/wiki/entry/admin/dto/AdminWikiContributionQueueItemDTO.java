package com.universe.wiki.entry.admin.dto;

import com.universe.wiki.application.contribution.query.WikiContributionAdminItem;

import java.util.Objects;

/**
 * Composite queue item containing the contribution domain item and enriched contributor identity.
 */
public record AdminWikiContributionQueueItemDTO(
        WikiContributionAdminItem item,
        AdminWikiContributionContributorDTO contributor
) {
    public AdminWikiContributionQueueItemDTO {
        Objects.requireNonNull(item, "item cannot be null");
        Objects.requireNonNull(contributor, "contributor cannot be null");
    }
}
