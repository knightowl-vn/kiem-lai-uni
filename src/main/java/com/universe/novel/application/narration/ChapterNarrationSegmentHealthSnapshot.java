package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Neutral input snapshot representing a CURRENT narration segment and its derived health status
 * for chapter narration generation planning (MS-04.9H.7C1A).
 *
 * @param segmentId    unique identity of the narration segment
 * @param segmentIndex 0-based position of the segment within the chapter
 * @param healthStatus derived health status of the segment audio
 */
public record ChapterNarrationSegmentHealthSnapshot(
        UUID segmentId,
        int segmentIndex,
        ChapterNarrationAudioHealthStatus healthStatus
) {
    public ChapterNarrationSegmentHealthSnapshot {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be non-negative: " + segmentIndex);
        }
        Objects.requireNonNull(healthStatus, "healthStatus must not be null");
    }
}
