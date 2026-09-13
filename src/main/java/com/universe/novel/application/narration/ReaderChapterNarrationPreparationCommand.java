package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Internal command for asynchronous Reader chapter narration preparation (MS-04.9H.9, H.9I5B).
 */
public record ReaderChapterNarrationPreparationCommand(
        UUID chapterId,
        UUID managedVoiceId
) {
    public ReaderChapterNarrationPreparationCommand {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
    }
}
