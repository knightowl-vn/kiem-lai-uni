package com.universe.novel.domain.narration;

/**
 * Lifecycle status of a persisted chapter narration text segment.
 */
public enum ChapterNarrationSegmentStatus {
    /**
     * Active narration segment currently mapped to the chapter's latest manifest.
     */
    CURRENT,

    /**
     * Unmatched narration segment preserved historically for potential future restoration or reference.
     */
    RETIRED
}
