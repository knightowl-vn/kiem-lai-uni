package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Public command for on-demand reader narration playback preparation by voice key (MS-04.9H.7D1A).
 *
 * @param chapterId identity of the published chapter
 * @param segmentId identity of the narration segment
 * @param voiceKey  unique public key of the managed voice
 */
public record PreparePublicReaderNarrationPlaybackCommand(
        UUID chapterId,
        UUID segmentId,
        String voiceKey
) {
    public PreparePublicReaderNarrationPlaybackCommand {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (voiceKey == null || voiceKey.isBlank()) {
            throw new IllegalArgumentException("voiceKey must not be null or blank");
        }
    }
}
