package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable command dispatched for asynchronous background reader narration continuation (MS-04.9H.7C2C3B).
 *
 * @param chapterId               identity of the chapter (non-null)
 * @param requestedSegmentId       identity of the segment requested by reader for immediate playback (non-null)
 * @param managedVoiceId          identity of the active managed voice (non-null)
 * @param refreshRequestedSegment whether the requested segment has cached OUTDATED audio that should be refreshed in background
 */
public record ReaderNarrationContinuationCommand(
        UUID chapterId,
        UUID requestedSegmentId,
        UUID managedVoiceId,
        boolean refreshRequestedSegment
) {
    public ReaderNarrationContinuationCommand {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(requestedSegmentId, "requestedSegmentId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
    }
}
