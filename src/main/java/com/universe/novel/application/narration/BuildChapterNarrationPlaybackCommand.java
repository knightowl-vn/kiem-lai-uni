package com.universe.novel.application.narration;

import java.util.UUID;

/**
 * Passive command for building one chapter narration playback candidate.
 */
public record BuildChapterNarrationPlaybackCommand(
        UUID chapterId,
        UUID managedVoiceId
) {
}
