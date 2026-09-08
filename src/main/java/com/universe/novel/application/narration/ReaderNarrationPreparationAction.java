package com.universe.novel.application.narration;

/**
 * Action determining playback and preparation behavior for a chapter narration segment on the reader side (MS-04.9H.7C2A).
 */
public enum ReaderNarrationPreparationAction {
    /**
     * Narration audio is READY and compatible with current voice synthesis revision.
     * Audio is immediately playable; no preparation or refresh required.
     */
    PLAY_NOW,

    /**
     * Narration audio exists but is OUTDATED relative to current voice synthesis revision.
     * Existing cached audio is playable now; background refresh is recommended and playback is NOT blocked.
     */
    PLAY_NOW_AND_REFRESH,

    /**
     * Narration audio is MISSING for this segment.
     * Audio is not playable now; preparation is required before playback can proceed.
     */
    PREPARE,

    /**
     * Narration audio generation previously FAILED for this segment.
     * Audio is not playable now; retry preparation is required before playback can proceed.
     */
    RETRY_PREPARE
}
