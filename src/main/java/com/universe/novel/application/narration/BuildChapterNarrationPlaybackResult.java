package com.universe.novel.application.narration;

import java.util.UUID;

/**
 * Result of publishing one newly built chapter narration playback artifact.
 */
public record BuildChapterNarrationPlaybackResult(
        UUID playbackId,
        UUID artifactId,
        UUID mediaAssetId
) {
}
