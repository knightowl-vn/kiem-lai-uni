package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to request atomic cleanup handoff of an obsolete narration audio assignment (MS-04.9H.7C1C1).
 *
 * @param narrationAudioId identity of the ChapterNarrationAudio assignment
 */
public record HandoffRetiredNarrationAudioCleanupCommand(
        UUID narrationAudioId
) {
    public HandoffRetiredNarrationAudioCleanupCommand {
        Objects.requireNonNull(narrationAudioId, "narrationAudioId must not be null");
    }
}
