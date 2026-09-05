package com.universe.novel.application.narration;

/**
 * Outcome resulting from a chapter narration audio regeneration attempt.
 */
public enum RegenerateNarrationAudioOutcome {
    /**
     * The existing audio assignment was already compatible with the current voice synthesis revision.
     */
    ALREADY_CURRENT,

    /**
     * The stale assignment was successfully updated with newly generated and stored audio.
     */
    REGENERATED
}
