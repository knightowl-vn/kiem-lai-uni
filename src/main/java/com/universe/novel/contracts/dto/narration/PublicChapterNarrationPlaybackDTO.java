package com.universe.novel.contracts.dto.narration;

import java.util.List;
import java.util.UUID;

/**
 * Passive public metadata for one chapter-level managed narration playback.
 */
public record PublicChapterNarrationPlaybackDTO(
        UUID chapterId,
        String voiceKey,
        PublicChapterNarrationPlaybackAvailability availability,
        PublicChapterNarrationPlaybackFreshness freshness,
        boolean playable,
        UUID artifactId,
        String audioUrl,
        String codecMimeType,
        Long durationMillis,
        List<PublicChapterNarrationPlaybackCueDTO> cues
) {
}
