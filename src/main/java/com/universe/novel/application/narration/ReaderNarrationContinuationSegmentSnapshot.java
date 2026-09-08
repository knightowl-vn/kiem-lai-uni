package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of a CURRENT narration segment's health for continuation planning (MS-04.9H.7C2C1).
 *
 * @param segmentId    identity of the narration segment (non-null)
 * @param segmentIndex zero-based index of the segment (>= 0)
 * @param health       audio health status (non-null)
 */
public record ReaderNarrationContinuationSegmentSnapshot(
        UUID segmentId,
        int segmentIndex,
        ChapterNarrationAudioHealthStatus health
) {
    public ReaderNarrationContinuationSegmentSnapshot {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must not be negative: " + segmentIndex);
        }
        Objects.requireNonNull(health, "health must not be null");
    }
}
