package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable result returned from on-demand reader narration segment preparation (MS-04.9H.7C2B).
 *
 * @param chapterId            identity of the chapter
 * @param segmentId            identity of the narration segment
 * @param segmentIndex         zero-based index of the segment
 * @param managedVoiceId       identity of the managed voice
 * @param initialHealth        initial health status derived before preparation
 * @param initialAction        initial reader action planned from initial health
 * @param finalHealth          authoritative health status derived after preparation (nullable if request became unavailable)
 * @param outcome              high-level preparation orchestration outcome
 * @param mediaAssetId         identifier of the playable media asset (nullable when not playable)
 * @param preparationAttempted whether generation primitive was invoked
 * @param refreshRecommended   whether background refresh/regeneration is recommended (true for OUTDATED audio)
 */
public record PrepareReaderNarrationSegmentResult(
        UUID chapterId,
        UUID segmentId,
        int segmentIndex,
        UUID managedVoiceId,
        ChapterNarrationAudioHealthStatus initialHealth,
        ReaderNarrationPreparationAction initialAction,
        ChapterNarrationAudioHealthStatus finalHealth,
        PrepareReaderNarrationSegmentOutcome outcome,
        UUID mediaAssetId,
        boolean preparationAttempted,
        boolean refreshRecommended
) {
    public PrepareReaderNarrationSegmentResult {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must not be negative: " + segmentIndex);
        }
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        Objects.requireNonNull(initialHealth, "initialHealth must not be null");
        Objects.requireNonNull(initialAction, "initialAction must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    /**
     * Returns true if audio is ready and available for immediate playback.
     */
    public boolean isPlayableNow() {
        return (outcome == PrepareReaderNarrationSegmentOutcome.PLAYABLE_CACHED
                || outcome == PrepareReaderNarrationSegmentOutcome.PREPARED_AND_PLAYABLE)
                && mediaAssetId != null;
    }

    /**
     * Returns true if audio is not playable and playback is blocked.
     */
    public boolean blocksPlayback() {
        return !isPlayableNow();
    }

    /**
     * Returns true if preparation was attempted and completed successfully into a playable state.
     */
    public boolean preparationSucceeded() {
        return outcome == PrepareReaderNarrationSegmentOutcome.PREPARED_AND_PLAYABLE;
    }
}
