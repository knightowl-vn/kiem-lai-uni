package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Result returned after processing a public chapter narration preparation command (MS-04.9H.9, H.9I5B).
 */
public record PreparePublicChapterNarrationPlaybackResult(
        UUID chapterId,
        String voiceKey,
        ReaderChapterNarrationPreparationDispatchStatus dispatchStatus
) {
    public PreparePublicChapterNarrationPlaybackResult {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(voiceKey, "voiceKey must not be null");
        Objects.requireNonNull(dispatchStatus, "dispatchStatus must not be null");
    }
}
