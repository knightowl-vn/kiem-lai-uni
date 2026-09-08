package com.universe.novel.application.narration;

import java.util.UUID;

/**
 * Identity of the new Media asset created for a chapter playback candidate.
 */
public record UploadChapterNarrationPlaybackMediaResult(
        UUID mediaAssetId
) {
}
