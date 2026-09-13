package com.universe.novel.application.narration;

/**
 * Derived health status of chapter narration audio for a segment and managed voice pair.
 */
public enum ChapterNarrationAudioHealthStatus {
    /**
     * No audio assignment exists and no unresolved generation failure has occurred.
     */
    MISSING,

    /**
     * An audio assignment exists and its generated synthesis revision matches the voice's current revision.
     */
    READY,

    /**
     * An audio assignment exists but its generated synthesis revision differs from the voice's current revision.
     */
    OUTDATED,

    /**
     * No audio assignment exists and an unresolved generation failure was recorded.
     */
    FAILED
}
