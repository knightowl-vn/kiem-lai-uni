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
        ChapterAudioSegmentBinarySource binarySource,
        Long encodedContributionSamples,
        Integer encodedSampleRateHz
) {

    /**
     * Backward-compatible constructor delegating timing to {@code null}/{@code null}.
     */
    public ChapterAudioSegmentSource(
            UUID segmentId,
            int segmentIndex,
            String mimeType,
            ChapterAudioSegmentBinarySource binarySource
    ) {
        this(segmentId, segmentIndex, mimeType, binarySource, null, null);
    }

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

        if ((encodedContributionSamples == null) != (encodedSampleRateHz == null)) {
            throw new IllegalArgumentException(
                    "encodedContributionSamples and encodedSampleRateHz must either both be null or both be non-null"
            );
        }
        if (encodedContributionSamples != null) {
            if (encodedContributionSamples <= 0) {
                throw new IllegalArgumentException(
                        "encodedContributionSamples must be > 0: " + encodedContributionSamples
                );
            }
            if (encodedSampleRateHz <= 0) {
                throw new IllegalArgumentException(
                        "encodedSampleRateHz must be > 0: " + encodedSampleRateHz
                );
            }
        }
    }
}
