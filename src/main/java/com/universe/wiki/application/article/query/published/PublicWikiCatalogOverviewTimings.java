package com.universe.wiki.application.article.query.published;

/**
 * Diagnostic nano-duration timings for the distinct read stages of the public Wiki catalog overview.
 */
public record PublicWikiCatalogOverviewTimings(
        long articleQueryNanos,
        long appreciationQueryNanos,
        long mediaBatchResolveNanos,
        long totalCoordinatorNanos,
        int eligibleAppreciationCount,
        int coverAssetCount
) {

    public static PublicWikiCatalogOverviewTimings empty() {
        return new PublicWikiCatalogOverviewTimings(0L, 0L, 0L, 0L, 0, 0);
    }
}
