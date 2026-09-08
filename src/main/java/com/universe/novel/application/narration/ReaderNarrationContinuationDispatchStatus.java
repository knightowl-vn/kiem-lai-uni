package com.universe.novel.application.narration;

/**
 * Dispatch status of background narration continuation for a chapter playback request (MS-04.9H.7C2C3B).
 */
public enum ReaderNarrationContinuationDispatchStatus {
    /**
     * Continuation task was accepted and scheduled for background execution.
     */
    SCHEDULED,

    /**
     * A continuation task for the same chapter and managed voice is already queued or actively running.
     */
    ALREADY_IN_FLIGHT,

    /**
     * Continuation task was rejected by the background executor (e.g. queue capacity full).
     */
    REJECTED,

    /**
     * Immediate requested segment was not playable; continuation was not scheduled.
     */
    NOT_SCHEDULED
}
