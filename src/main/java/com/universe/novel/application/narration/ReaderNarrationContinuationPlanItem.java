package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Individual ordered work item in a reader narration continuation plan (MS-04.9H.7C2C1).
 *
 * @param segmentId    identity of the narration segment (non-null)
 * @param segmentIndex zero-based index of the segment (>= 0)
 * @param health       audio health status at planning time (non-null)
 * @param action       continuation action to execute (non-null)
 */
public record ReaderNarrationContinuationPlanItem(
        UUID segmentId,
        int segmentIndex,
        ChapterNarrationAudioHealthStatus health,
        ReaderNarrationContinuationAction action
) {
    public ReaderNarrationContinuationPlanItem {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must not be negative: " + segmentIndex);
        }
        Objects.requireNonNull(health, "health must not be null");
        Objects.requireNonNull(action, "action must not be null");
    }
}
