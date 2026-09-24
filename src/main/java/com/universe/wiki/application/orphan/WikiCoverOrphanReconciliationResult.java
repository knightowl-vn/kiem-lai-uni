package com.universe.wiki.application.orphan;

/**
 * Result metrics for a single reconciliation execution run.
 */
public record WikiCoverOrphanReconciliationResult(
        int recoveredStaleLeases,
        int eligibleClaimed,
        int successfullyDeleted,
        int activeReReferenced,
        int failedAttempts
) {
}
