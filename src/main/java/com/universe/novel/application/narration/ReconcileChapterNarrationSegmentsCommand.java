package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Command for reconciling narration text segments for a given chapter.
 *
 * @param chapterId ID of the novel chapter to reconcile
 */
public record ReconcileChapterNarrationSegmentsCommand(
        UUID chapterId
) {
    public ReconcileChapterNarrationSegmentsCommand {
        Objects.requireNonNull(chapterId, "Chapter ID must not be null.");
    }
}
