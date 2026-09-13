package com.universe.novel.application.narration;

/** Admin/build state; content and voice staleness follow the existing H.9F revision rules. */
public enum ChapterNarrationPlaybackState {
    MISSING,
    CURRENT,
    STALE_CONTENT,
    STALE_VOICE,
    STALE_SOURCE
}
