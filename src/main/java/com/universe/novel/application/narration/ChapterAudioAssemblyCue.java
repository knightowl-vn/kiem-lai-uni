package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable cue mapping one chapter-audio interval to one narration segment contribution.
 */
public record ChapterAudioAssemblyCue(
        int cueOrdinal,
        UUID segmentId,
        int segmentIndex,
        long startMillis,
        long endMillis
) {
    public ChapterAudioAssemblyCue {
        if (cueOrdinal < 0) {
            throw new IllegalArgumentException("cueOrdinal must be >= 0: " + cueOrdinal);
        }
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be >= 0: " + segmentIndex);
        }
        if (startMillis < 0) {
            throw new IllegalArgumentException("startMillis must be >= 0: " + startMillis);
        }
        if (endMillis <= startMillis) {
            throw new IllegalArgumentException("endMillis must be greater than startMillis");
        }
    }
}
