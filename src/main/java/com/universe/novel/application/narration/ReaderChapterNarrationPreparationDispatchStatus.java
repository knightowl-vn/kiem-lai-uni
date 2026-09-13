package com.universe.novel.application.narration;

/**
 * Dispatch disposition for Reader chapter narration preparation requests (MS-04.9H.9, H.9I5B).
 */
public enum ReaderChapterNarrationPreparationDispatchStatus {
    SCHEDULED,
    ALREADY_IN_FLIGHT,
    REJECTED
}
