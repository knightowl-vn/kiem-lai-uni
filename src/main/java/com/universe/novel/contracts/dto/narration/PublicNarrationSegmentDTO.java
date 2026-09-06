package com.universe.novel.contracts.dto.narration;

import java.util.UUID;

/**
 * Public, playback-safe DTO representing a narration segment and its audio delivery status (MS-04.9H.7A).
 */
public record PublicNarrationSegmentDTO(
        UUID segmentId,
        int segmentIndex,
        String healthStatus,
        boolean playable,
        String audioUrl
) {
}
