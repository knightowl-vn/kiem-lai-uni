package com.universe.novel.application.narration;

import java.util.UUID;

/**
 * Result committed by the short chapter playback finalization transaction.
 */
public record FinalizeChapterNarrationPlaybackResult(
        UUID playbackId,
        UUID artifactId,
        UUID mediaAssetId,
        UUID supersededMediaAssetId
) {
}
