package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Application command to generate or resolve narration audio for a single segment and managed voice.
 *
 * @param segmentId      identity of the chapter narration segment
 * @param managedVoiceId identity of the managed voice
 */
public record GenerateChapterNarrationAudioCommand(
        UUID segmentId,
        UUID managedVoiceId
) {
    public GenerateChapterNarrationAudioCommand {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
    }
}
