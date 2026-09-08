package com.universe.novel.application.narration;

import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot used to reject stale chapter playback candidates at finalization.
 */
public record ChapterNarrationPlaybackBuildSnapshot(
        UUID chapterId,
        UUID managedVoiceId,
        long sourceContentVersion,
        long synthesisRevision,
        String manifestHash,
        List<ChapterNarrationPlaybackSegmentSnapshot> segments
) {
    public ChapterNarrationPlaybackBuildSnapshot {
        segments = segments == null ? null : List.copyOf(segments);
    }
}
