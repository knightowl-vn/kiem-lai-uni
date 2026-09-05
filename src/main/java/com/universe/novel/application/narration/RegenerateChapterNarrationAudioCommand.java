package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to regenerate narration audio for an existing chapter narration audio assignment.
 *
 * @param segmentId      identity of the chapter narration segment
 * @param managedVoiceId identity of the managed voice
 */
public record RegenerateChapterNarrationAudioCommand(
        UUID segmentId,
        UUID managedVoiceId
) {
    public RegenerateChapterNarrationAudioCommand {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
    }
}
