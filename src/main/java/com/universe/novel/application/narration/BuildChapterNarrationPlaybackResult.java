package com.universe.novel.application.narration;

import java.util.UUID;

/**
 * BUILT covers initial publication and replacement; ALREADY_CURRENT retains the existing artifact.
 */
public record BuildChapterNarrationPlaybackResult(
        UUID playbackId,
        UUID artifactId,
        UUID mediaAssetId,
        BuildChapterNarrationPlaybackOutcome outcome
) {
    public BuildChapterNarrationPlaybackResult(UUID playbackId, UUID artifactId, UUID mediaAssetId) {
        this(playbackId, artifactId, mediaAssetId, BuildChapterNarrationPlaybackOutcome.BUILT);
    }
}
