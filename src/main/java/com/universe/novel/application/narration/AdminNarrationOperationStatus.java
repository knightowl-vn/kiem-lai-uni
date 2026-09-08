package com.universe.novel.application.narration;

/**
 * Operation lifecycle status for Admin-initiated whole-chapter Managed narration generation (MS-04.9H.7D4A).
 */
public enum AdminNarrationOperationStatus {
    /**
     * No narration generation operation has been initiated or the state has been reset.
     */
    IDLE,

    /**
     * A chapter-level narration generation operation is actively queued or running.
     */
    RUNNING,

    /**
     * All planned segments were successfully generated, regenerated, or skipped (zero failures).
     */
    SUCCEEDED,

    /**
     * Generation completed with partial success (some segments succeeded/skipped, but one or more failed or need retry).
     */
    PARTIAL,

    /**
     * Generation failed completely or encountered an unrecoverable exception.
     */
    FAILED
}
