package com.universe.novel.application.narration;

/**
 * High-level coordinator status for chapter completion cleanup (MS-04.9H.7C1C2A).
 */
public enum ChapterNarrationCompletionCleanupStatus {
    /**
     * Chapter narration is not eligible for cleanup (e.g. manifest absent, voice inactive, or CURRENT segments not all READY).
     */
    NOT_ELIGIBLE,

    /**
     * Cleanup completed successfully for all candidate retired audio assignments (or no retired audio exists).
     */
    COMPLETED,

    /**
     * Cleanup was partially completed (e.g. some candidates were skipped or failed with an exception).
     */
    PARTIAL,

    /**
     * Cleanup was aborted because chapter narration manifest changed (or disappeared) during execution.
     */
    MANIFEST_CHANGED,

    /**
     * Completion cleanup coordinator encountered an unexpected top-level exception.
     */
    COORDINATOR_FAILED
}
