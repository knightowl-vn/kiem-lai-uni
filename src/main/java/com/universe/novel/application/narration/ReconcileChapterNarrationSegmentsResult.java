package com.universe.novel.application.narration;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Passive immutable result record representing a deterministic narration segment reconciliation snapshot.
 *
 * @param sourceContentVersion the chapter content version that was segmented
 * @param manifestHash         the deterministic 64-character lowercase hexadecimal SHA-256 manifest hash
 * @param currentSegments      immutable list of reconciled CURRENT segment items, ordered by segmentIndex ASC
 * @param retiredSegmentIds    immutable list of segment IDs transitioned to RETIRED
 */
public record ReconcileChapterNarrationSegmentsResult(
        long sourceContentVersion,
        String manifestHash,
        List<ChapterNarrationSegmentReconciliationItem> currentSegments,
        List<UUID> retiredSegmentIds
) {
    public ReconcileChapterNarrationSegmentsResult {
        if (sourceContentVersion < 1) {
            throw new IllegalArgumentException("sourceContentVersion must be >= 1: " + sourceContentVersion);
        }
        Objects.requireNonNull(manifestHash, "manifestHash must not be null");
        Objects.requireNonNull(currentSegments, "currentSegments must not be null");
        Objects.requireNonNull(retiredSegmentIds, "retiredSegmentIds must not be null");

        currentSegments = List.copyOf(currentSegments);
        retiredSegmentIds = List.copyOf(retiredSegmentIds);
    }

    /**
     * Total number of active CURRENT segments after reconciliation.
     */
    public int currentSegmentCount() {
        return currentSegments.size();
    }

    /**
     * Number of existing CURRENT segments that were reused (both unchanged and repositioned).
     */
    public int reusedCurrentCount() {
        return unchangedCurrentCount() + repositionedCurrentCount();
    }

    /**
     * Number of existing CURRENT segments that remained at the exact same index.
     */
    public int unchangedCurrentCount() {
        return (int) currentSegments.stream()
                .filter(item -> item.disposition() == ChapterNarrationSegmentReconciliationDisposition.REUSED_UNCHANGED)
                .count();
    }

    /**
     * Number of existing CURRENT segments that were repositioned to a new index.
     */
    public int repositionedCurrentCount() {
        return (int) currentSegments.stream()
                .filter(item -> item.disposition() == ChapterNarrationSegmentReconciliationDisposition.REUSED_REPOSITIONED)
                .count();
    }

    /**
     * Number of previously RETIRED segments that were restored to CURRENT.
     */
    public int restoredCount() {
        return (int) currentSegments.stream()
                .filter(item -> item.disposition() == ChapterNarrationSegmentReconciliationDisposition.RESTORED)
                .count();
    }

    /**
     * Number of newly created segments.
     */
    public int createdCount() {
        return (int) currentSegments.stream()
                .filter(item -> item.disposition() == ChapterNarrationSegmentReconciliationDisposition.CREATED)
                .count();
    }

    /**
     * Number of previously CURRENT segments that became RETIRED.
     */
    public int retiredCount() {
        return retiredSegmentIds.size();
    }
}
