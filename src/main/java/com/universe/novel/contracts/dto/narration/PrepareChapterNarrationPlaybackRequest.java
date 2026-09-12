package com.universe.novel.contracts.dto.narration;

/**
 * Public Reader request for chapter-level narration playback preparation (MS-04.9H.9, H.9I5B).
 */
public record PrepareChapterNarrationPlaybackRequest(
        String voiceKey
) {
}
