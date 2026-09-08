package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Command for preparing a single CURRENT narration segment on demand for immediate reader playback (MS-04.9H.7C2B).
 *
 * @param chapterId      identity of the published chapter
 * @param segmentId      identity of the CURRENT narration segment
 * @param managedVoiceId identity of the active managed voice
 */
public record PrepareReaderNarrationSegmentCommand(
        UUID chapterId,
        UUID segmentId,
        UUID managedVoiceId
) {
    public PrepareReaderNarrationSegmentCommand {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
    }
}
