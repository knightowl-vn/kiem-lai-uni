package com.universe.novel.application.narration;

/**
 * Dispatch status returned upon submitting an Admin narration generation request (MS-04.9H.7D4A).
 */
public enum AdminNarrationDispatchStatus {
    /**
     * The generation task was successfully accepted and scheduled for background execution.
     */
    STARTED,

    /**
     * A generation task for the specified chapter and voice is already actively running or queued.
     */
    ALREADY_RUNNING,

    /**
     * The task was rejected because the background executor's bounded queue is saturated.
     */
    REJECTED,

    /**
     * Dispatch failed due to invalid arguments or preflight validation failure.
     */
    FAILED
}
