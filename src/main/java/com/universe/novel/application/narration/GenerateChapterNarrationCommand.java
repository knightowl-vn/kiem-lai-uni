package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Application command to execute narration generation across all CURRENT segments of a chapter for a managed voice (MS-04.9H.7C1B).
 *
 * @param chapterId      identity of the published chapter
 * @param managedVoiceId identity of the active managed voice
 */
public record GenerateChapterNarrationCommand(
        UUID chapterId,
        UUID managedVoiceId
) {
    public GenerateChapterNarrationCommand {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
    }
}
