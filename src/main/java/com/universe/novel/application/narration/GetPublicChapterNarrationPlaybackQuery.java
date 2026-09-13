package com.universe.novel.application.narration;

import java.util.UUID;

/**
 * Passive query for public chapter-level narration playback metadata.
 */
public record GetPublicChapterNarrationPlaybackQuery(
        UUID chapterId,
        String voiceKey
) {
}
