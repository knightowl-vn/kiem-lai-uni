package com.universe.novel.application.narration;

/**
 * Outcome of a narration media cleanup request (MS-04.9H.8D2A).
 */
public enum NarrationMediaCleanupOutcome {

    /**
     * Media logical deletion completed immediately.
     * Pending cleanup-intent removal is best-effort and may remain for the background processor to clear.
     */
    IMMEDIATELY_DELETED,

    /**
     * Immediate deletion failed, but a durable cleanup task was established/retained for asynchronous retry.
     */
    ENQUEUED_FOR_RETRY
}
