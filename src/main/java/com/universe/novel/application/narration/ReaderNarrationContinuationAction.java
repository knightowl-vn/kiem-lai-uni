package com.universe.novel.application.narration;

/**
 * Action to be executed for a narration segment during background reader continuation (MS-04.9H.7C2C1).
 */
public enum ReaderNarrationContinuationAction {
    /**
     * Synthesize and persist narration audio for a MISSING or FAILED segment.
     */
    GENERATE,

    /**
     * Re-synthesize and persist updated narration audio for an OUTDATED segment.
     */
    REGENERATE
}
