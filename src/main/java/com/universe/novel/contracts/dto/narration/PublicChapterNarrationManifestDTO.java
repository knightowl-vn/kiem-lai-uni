package com.universe.novel.contracts.dto.narration;

import java.util.List;
import java.util.UUID;

/**
 * Public, playback-safe manifest DTO for chapter narration audio delivery (MS-04.9H.7A).
 */
public record PublicChapterNarrationManifestDTO(
        UUID chapterId,
        List<PublicNarrationVoiceDTO> availableVoices,
        PublicNarrationVoiceDTO selectedVoice,
        List<PublicNarrationSegmentDTO> segments
) {
}
