package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Command for coordinating retired narration audio cleanup after chapter generation (MS-04.9H.7C1C2).
 */
public record CleanupCompletedChapterNarrationRetiredAudioCommand(
        UUID chapterId,
        UUID managedVoiceId
) {
    public CleanupCompletedChapterNarrationRetiredAudioCommand {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
    }
}
