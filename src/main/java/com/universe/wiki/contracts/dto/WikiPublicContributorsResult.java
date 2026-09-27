package com.universe.wiki.contracts.dto;

import java.util.List;

/**
 * Public read result model for acknowledged contributors on a published Wiki article.
 *
 * <p>Contains the bounded list of public contributor profiles (up to 50) and a flag indicating
 * whether the database candidate query reached the upper bound limit (> 50 candidates).
 *
 * <p>Never claims an exact global total and never exposes technical UUIDs or administrative metadata.
 */
public record WikiPublicContributorsResult(
        List<WikiPublicContributorDTO> contributors,
        boolean candidateLimitReached
) {
    public WikiPublicContributorsResult {
        contributors = contributors != null ? List.copyOf(contributors) : List.of();
    }

    public static WikiPublicContributorsResult empty() {
        return new WikiPublicContributorsResult(List.of(), false);
    }
}
