package com.universe.novel.application.narration;

import java.time.Instant;

/** Safe Admin metadata without source fingerprint, Media identity, or storage details. */
public record AdminChapterNarrationPlaybackDTO(
        ChapterNarrationPlaybackState state,
        Long durationMillis,
        Integer cueCount,
        Instant generatedAt
) {
    public static AdminChapterNarrationPlaybackDTO missing() {
        return new AdminChapterNarrationPlaybackDTO(ChapterNarrationPlaybackState.MISSING, null, null, null);
    }
}
