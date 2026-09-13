package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Passive immutable record representing one CURRENT narration segment in the reconciled manifest.
 *
 * @param segmentId   the aggregate UUID of the narration segment
 * @param segmentIndex 0-based ordered position in the reconciled manifest
 * @param disposition reconciliation disposition classification
 */
public record ChapterNarrationSegmentReconciliationItem(
        UUID segmentId,
        int segmentIndex,
        ChapterNarrationSegmentReconciliationDisposition disposition
) {
    public ChapterNarrationSegmentReconciliationItem {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be non-negative: " + segmentIndex);
        }
        Objects.requireNonNull(disposition, "disposition must not be null");
    }
}
