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
        MediaAssetVersionSnapshotDTO sourceMediaVersion
) {
}
