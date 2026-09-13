package com.universe.novel.application.narration;

/**
 * Outcome resulting from executing a planned chapter narration generation action for a segment (MS-04.9H.7C1B).
 */
public enum ChapterNarrationGenerationExecutionOutcome {
    /**
     * Segment audio was already READY; generation or regeneration was skipped.
     */
    SKIPPED_READY,

    /**
     * Audio was synthesized via TTS, uploaded to Media, and a new assignment was created.
     */
    GENERATED,

    /**
     * Existing compatible audio was discovered/reused during initial generation (e.g. concurrent race winner).
     */
    REUSED,

    /**
     * Stale audio assignment was replaced with freshly synthesized and uploaded audio.
     */
    REGENERATED,

    /**
     * Audio was discovered to be already current and compatible during regeneration.
     */
    ALREADY_CURRENT,

    /**
     * Stale assignment was encountered during initial generation; regeneration is required.
     */
    RETRY_REQUIRED,

    /**
     * Generation or regeneration primitive threw an exception and failed for this segment.
     */
    FAILED;

    public boolean isCompletedWork() {
        return this == GENERATED || this == REUSED || this == REGENERATED || this == ALREADY_CURRENT;
    }

    public boolean isSuccess() {
        return this != FAILED && this != RETRY_REQUIRED;
    }
}
