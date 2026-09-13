package com.universe.novel.infrastructure.persistence.chapter;

/**
 * Lightweight public narration projection for an eligible Reader chapter.
 */
public interface ReadableChapterNarrationAccessProjection {

    String getId();

    Long getContentVersion();
}
