package com.universe.novel.application.narration;

/**
 * Outcome resulting from a chapter narration audio generation attempt.
 */
public enum NarrationAudioGenerationOutcome {
    /**
     * An existing audio assignment was found and its synthesis revision matches the voice; audio was reused.
     */
    REUSED,

    /**
     * No audio assignment existed; TTS was called, Media stored the binary, and a new assignment was created.
     */
    GENERATED,

    /**
     * An existing audio assignment exists but its synthesis revision does not match the voice.
     * Stale assignment is not regenerated in this flow (regeneration is handled by H.5D).
     */
    STALE
}
