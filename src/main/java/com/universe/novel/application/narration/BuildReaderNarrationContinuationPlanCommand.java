package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Command for building a fresh prioritized continuation plan for background reader narration (MS-04.9H.7C2C3A).
 *
 * @param chapterId          identity of the published chapter (non-null)
 * @param requestedSegmentId identity of the segment currently requested for immediate reader playback (non-null)
 * @param managedVoiceId     identity of the active managed voice (non-null)
 */
public record BuildReaderNarrationContinuationPlanCommand(
        UUID chapterId,
        UUID requestedSegmentId,
        UUID managedVoiceId
) {
    public BuildReaderNarrationContinuationPlanCommand {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(requestedSegmentId, "requestedSegmentId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
    }
}
