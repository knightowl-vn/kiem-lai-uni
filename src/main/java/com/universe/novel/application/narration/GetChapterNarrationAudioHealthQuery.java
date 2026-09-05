package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Query to inspect the derived health of chapter narration audio for a segment and managed voice pair.
 *
 * @param segmentId      identity of the chapter narration segment
 * @param managedVoiceId identity of the managed voice
 */
public record GetChapterNarrationAudioHealthQuery(
        UUID segmentId,
        UUID managedVoiceId
) {
    public GetChapterNarrationAudioHealthQuery {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
    }
}
