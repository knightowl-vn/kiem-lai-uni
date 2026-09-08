package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Passive input model for one ordered narration segment audio source.
 */
public record ChapterAudioSegmentSource(
        UUID segmentId,
        int segmentIndex,
        String mimeType,
        ChapterAudioSegmentBinarySource binarySource
) {
    public ChapterAudioSegmentSource {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be >= 0: " + segmentIndex);
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be blank");
        }
        mimeType = mimeType.trim();
        Objects.requireNonNull(binarySource, "binarySource must not be null");
    }
}
