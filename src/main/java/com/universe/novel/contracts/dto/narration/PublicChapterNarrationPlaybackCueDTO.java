package com.universe.novel.contracts.dto.narration;

import java.util.UUID;

/**
 * Public cue in an immutable chapter narration playback timeline.
 */
public record PublicChapterNarrationPlaybackCueDTO(
        int cueOrdinal,
        UUID segmentId,
        int segmentIndex,
        long startMillis,
        long endMillis
) {
}
