package com.universe.novel.application.narration;

/**
 * Application-level disposition classification for a narration segment resulting from reconciliation.
 */
public enum ChapterNarrationSegmentReconciliationDisposition {
    REUSED_UNCHANGED,
    REUSED_REPOSITIONED,
    RESTORED,
    CREATED
}
