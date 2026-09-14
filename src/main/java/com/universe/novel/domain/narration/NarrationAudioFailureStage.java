package com.universe.novel.domain.narration;

/**
 * Execution stage at which a narration audio generation failure occurred.
 */
public enum NarrationAudioFailureStage {
    /**
     * Failed during external TTS audio synthesis.
     */
    TTS_SYNTHESIS,

    /**
     * Failed during segment audio encoding.
     */
    AUDIO_ENCODING,

    /**
     * Failed during Media platform binary upload.
     */
    MEDIA_UPLOAD,

    /**
     * Failed during local ChapterNarrationAudio assignment persistence.
     */
    ASSIGNMENT_PERSISTENCE
}
