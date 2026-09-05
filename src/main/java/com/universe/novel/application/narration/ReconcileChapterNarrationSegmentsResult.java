package com.universe.novel.application.narration;

/**
 * Passive result record containing reconciliation counts for chapter narration segments.
 *
 * @param currentSegmentCount total number of active CURRENT segments after reconciliation
 * @param reusedCurrentCount  number of existing CURRENT segments that were reused (at same or repositioned index)
 * @param restoredCount       number of previously RETIRED segments that were restored to CURRENT
 * @param createdCount        number of newly created segments
 * @param retiredCount        number of previously CURRENT segments that became RETIRED
 */
public record ReconcileChapterNarrationSegmentsResult(
        int currentSegmentCount,
        int reusedCurrentCount,
        int restoredCount,
        int createdCount,
        int retiredCount
) {
}
