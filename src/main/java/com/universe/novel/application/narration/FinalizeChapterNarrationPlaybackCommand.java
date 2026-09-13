package com.universe.novel.application.narration;

import java.util.List;
import java.util.UUID;

/**
 * Passive input to the short chapter playback finalization transaction.
 */
public record FinalizeChapterNarrationPlaybackCommand(
        ChapterNarrationPlaybackBuildSnapshot snapshot,
        UUID candidateMediaAssetId,
        long durationMillis,
        List<ChapterAudioAssemblyCue> cues
) {
    public FinalizeChapterNarrationPlaybackCommand {
        cues = cues == null ? null : List.copyOf(cues);
    }
}
