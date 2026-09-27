package com.universe.wiki.application.article.cover.backfill;

/**
 * Result telemetry record for a single Wiki cover legacy client-tag backfill execution run (MS-05G8C6).
 *
 * <p>Note on {@code successfulAssignmentsOrAlreadyTagged}: The underlying Media public contract
 * {@code MediaContract.assignClientTagIfAbsent} performs an idempotent assignment returning {@code void}
 * without distinguishing between a brand-new assignment and a pre-existing identical tag. In accordance with
 * Media API stability principles, this metric combines newly tagged assets and idempotent same-tag confirmations.
 */
public record WikiCoverLegacyTagBackfillResult(
        int scannedAssets,
        int successfulAssignmentsOrAlreadyTagged,
        int conflicts,
        int missingMediaAssets,
        int failedAssets
) {
    public static WikiCoverLegacyTagBackfillResult empty() {
        return new WikiCoverLegacyTagBackfillResult(0, 0, 0, 0, 0);
    }
}
