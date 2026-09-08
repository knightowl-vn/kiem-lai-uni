package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable decision record mapping a CURRENT segment's health to reader playback and preparation behavior (MS-04.9H.7C2A).
 *
 * @param segmentId    identity of the narration segment
 * @param segmentIndex zero-based index of the segment in the chapter
 * @param health       current health status of the segment audio
 * @param action       reader preparation/playback action
 */
public record ReaderNarrationPreparationDecision(
        UUID segmentId,
        int segmentIndex,
        ChapterNarrationAudioHealthStatus health,
        ReaderNarrationPreparationAction action
) {
    public ReaderNarrationPreparationDecision {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must not be negative: " + segmentIndex);
        }
        Objects.requireNonNull(health, "health must not be null");
        Objects.requireNonNull(action, "action must not be null");
    }

    /**
     * Returns true if audio is available for immediate playback (either fresh READY or cached OUTDATED).
     */
    public boolean isPlayableNow() {
        return action == ReaderNarrationPreparationAction.PLAY_NOW
                || action == ReaderNarrationPreparationAction.PLAY_NOW_AND_REFRESH;
    }

    /**
     * Returns true if audio must be generated before playback can proceed (MISSING or FAILED).
     */
    public boolean requiresImmediatePreparation() {
        return action == ReaderNarrationPreparationAction.PREPARE
                || action == ReaderNarrationPreparationAction.RETRY_PREPARE;
    }

    /**
     * Returns true if audio is playable but regeneration is recommended due to an outdated voice synthesis revision.
     */
    public boolean recommendsRefresh() {
        return action == ReaderNarrationPreparationAction.PLAY_NOW_AND_REFRESH;
    }

    /**
     * Returns true if playback cannot begin until preparation completes.
     */
    public boolean blocksPlayback() {
        return action == ReaderNarrationPreparationAction.PREPARE
                || action == ReaderNarrationPreparationAction.RETRY_PREPARE;
    }
}
