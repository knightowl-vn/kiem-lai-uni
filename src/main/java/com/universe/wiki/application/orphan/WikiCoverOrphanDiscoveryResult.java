package com.universe.wiki.application.orphan;

/**
 * Result metrics for a single discovery execution run.
 */
public record WikiCoverOrphanDiscoveryResult(
        int scannedCandidates,
        int observedOrphans,
        int activeReferenced,
        int failedInspections,
        int deletingPreserved
) {
    public WikiCoverOrphanDiscoveryResult(
            int scannedCandidates,
            int observedOrphans,
            int activeReferenced,
            int failedInspections
    ) {
        this(scannedCandidates, observedOrphans, activeReferenced, failedInspections, 0);
    }
}
