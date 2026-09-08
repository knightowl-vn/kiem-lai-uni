package com.universe.novel.application.narration;

/**
 * Outcome returned by {@link HandoffRetiredNarrationAudioCleanupUseCase} (MS-04.9H.7C1C1).
 */
public enum HandoffRetiredNarrationAudioCleanupOutcome {
    /**
     * Obsolete narration audio assignment was successfully deleted and durable Media cleanup was enqueued atomically.
     */
    HANDED_OFF,

    /**
     * The requested narration audio assignment is already absent from storage (idempotent no-op).
     */
    ALREADY_ABSENT,

    /**
     * The owning narration segment is not in RETIRED status (e.g. CURRENT); cleanup was skipped.
     */
    SKIPPED_NOT_RETIRED,

    /**
     * The Media asset is still referenced by another ChapterNarrationAudio assignment; assignment was not deleted and cleanup was skipped.
     */
    SKIPPED_SHARED_MEDIA_REFERENCE;

    public boolean isHandedOff() {
        return this == HANDED_OFF;
    }

    public boolean isAlreadyAbsent() {
        return this == ALREADY_ABSENT;
    }

    public boolean isSkipped() {
        return this == SKIPPED_NOT_RETIRED || this == SKIPPED_SHARED_MEDIA_REFERENCE;
    }
}
