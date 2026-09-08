package com.universe.novel.application.narration;

/**
 * Outcome of executing an individual continuation plan item (MS-04.9H.7C2C2).
 */
public enum ReaderNarrationContinuationItemOutcome {
    /**
     * Fresh health check proved the segment was already READY; external synthesis/upload was skipped.
     */
    SKIPPED_ALREADY_READY,

    /**
     * Generation or regeneration was executed (or resolved via race winner) and final health is READY.
     */
    COMPLETED_READY,

    /**
     * Generation or regeneration was executed but voice revision changed or audio remains OUTDATED.
     */
    COMPLETED_OUTDATED,

    /**
     * Execution failed and recorded an unresolved failure; retry is required.
     */
    RETRY_REQUIRED,

    /**
     * Execution failed and audio remains missing.
     */
    FAILED,

    /**
     * Segment or manifest became invalid, retired, or re-associated during execution.
     */
    STALE_CONTEXT
}
