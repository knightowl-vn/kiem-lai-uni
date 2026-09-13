package com.universe.novel.domain.narration;

/**
 * Operation during which narration audio generation was attempted.
 */
public enum NarrationAudioOperation {
    /**
     * First-time generation attempt when no prior audio assignment existed.
     */
    INITIAL_GENERATION,

    /**
     * Regeneration attempt for an existing stale audio assignment.
     */
    REGENERATION
}
