package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Public command for chapter narration preparation (MS-04.9H.9, H.9I5B).
 */
public record PreparePublicChapterNarrationPlaybackCommand(
        UUID chapterId,
        String voiceKey
) {
    public PreparePublicChapterNarrationPlaybackCommand {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(voiceKey, "voiceKey must not be null");
    }
}
