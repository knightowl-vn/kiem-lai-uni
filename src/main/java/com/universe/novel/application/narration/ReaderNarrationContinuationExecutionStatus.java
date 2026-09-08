package com.universe.novel.application.narration;

/**
 * High-level execution status of a reader narration continuation plan (MS-04.9H.7C2C2).
 */
public enum ReaderNarrationContinuationExecutionStatus {
    /**
     * All continuation plan items were processed to completion (including successful generations, skips, and isolated item failures).
     */
    COMPLETED,

    /**
     * Processing stopped partially before all items were attempted.
     */
    PARTIAL,

    /**
     * Processing was stopped because the chapter narration manifest changed or disappeared (e.g. due to chapter republish).
     */
    MANIFEST_CHANGED,

    /**
     * Processing was stopped because the chapter, voice, or segment became unavailable or retired.
     */
    CONTEXT_UNAVAILABLE
}
