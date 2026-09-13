package com.universe.novel.contracts.dto.narration;

import java.util.UUID;

/**
 * Public response returned when chapter narration preparation is accepted (MS-04.9H.9, H.9I5B).
 */
public record PublicChapterNarrationPrepareResponseDTO(
        UUID chapterId,
        String voiceKey,
        PublicChapterNarrationPlaybackAvailability availability
) {
}
