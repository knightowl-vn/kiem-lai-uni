package com.universe.wiki.application.article.query.published;

/**
 * Diagnostic nano-duration timings for the distinct read stages of the public Wiki article detail coordinator.
 */
public record PublicWikiArticleDetailTimings(
        long articleQueryNanos,
        long savedStateQueryNanos,
        long coverResolveNanos,
        long appreciationQueryNanos,
        long attributionQueryNanos,
        long contributorsQueryNanos,
        long totalCoordinatorNanos,
        boolean authenticatedViewer,
        boolean coverPresent,
        boolean coverResolved,
        boolean appreciationEligible,
        boolean attributionResolved,
        int contributorCount
) {

    public static PublicWikiArticleDetailTimings empty() {
        return new PublicWikiArticleDetailTimings(
                0L, 0L, 0L, 0L, 0L, 0L, 0L,
                false, false, false, false, false, 0
        );
    }
}
