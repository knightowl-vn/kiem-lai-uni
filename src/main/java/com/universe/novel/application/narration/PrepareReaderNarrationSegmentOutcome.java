package com.universe.novel.application.narration;

/**
 * Orchestration outcome for on-demand reader narration segment preparation (MS-04.9H.7C2B).
 */
public enum PrepareReaderNarrationSegmentOutcome {
    /**
     * Narration audio was already cached and playable (either fresh READY or cached OUTDATED); no preparation was executed.
     */
    PLAYABLE_CACHED,

    /**
     * Preparation was executed and the resulting audio is now playable (freshly generated, reused from a concurrent winner, or compatible).
     */
    PREPARED_AND_PLAYABLE,

    /**
     * Preparation was attempted but ended in a recorded failure; retry preparation is required before playback can proceed.
     */
    RETRY_REQUIRED,

    /**
     * Preparation failed or audio remains missing; playback cannot proceed.
     */
    PREPARATION_FAILED,

    /**
     * The requested chapter, segment, or voice became unavailable, retired, or invalid.
     */
    UNAVAILABLE
}
