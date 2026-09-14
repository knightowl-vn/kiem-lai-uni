package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO;

import java.util.UUID;

/**
 * Immutable build-time provenance for one ordered CURRENT narration segment.
 */
public record ChapterNarrationPlaybackSegmentSnapshot(
        UUID segmentId,
        int segmentIndex,
        String contentHash,
        UUID narrationAudioId,
        Long narrationAudioVersion,
        UUID mediaAssetId,
        long generatedSynthesisRevision,
        MediaAssetVersionSnapshotDTO sourceMediaVersion,
        Long encodedContributionSamples,
        Integer encodedSampleRateHz
) {

    /**
     * Backward-compatible constructor delegating timing to {@code null}/{@code null}.
     */
    public ChapterNarrationPlaybackSegmentSnapshot(
            UUID segmentId,
            int segmentIndex,
            String contentHash,
            UUID narrationAudioId,
            Long narrationAudioVersion,
            UUID mediaAssetId,
            long generatedSynthesisRevision,
            MediaAssetVersionSnapshotDTO sourceMediaVersion
    ) {
        this(
                segmentId,
                segmentIndex,
                contentHash,
                narrationAudioId,
                narrationAudioVersion,
                mediaAssetId,
                generatedSynthesisRevision,
                sourceMediaVersion,
                null,
                null
        );
    }
}
