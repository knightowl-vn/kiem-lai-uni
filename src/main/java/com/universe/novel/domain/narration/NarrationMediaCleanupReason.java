package com.universe.novel.domain.narration;

/**
 * Domain enumeration indicating the rationale why a Media asset is obsolete
 * and queued for cleanup by the Narration module (MS-04.9H.8D1A).
 */
public enum NarrationMediaCleanupReason {
    /**
     * A newly generated Media asset was unreferenced due to an assignment persistence failure or concurrency race loss.
     */
    UNREFERENCED_GENERATED_ASSET,

    /**
     * An existing Media asset was superseded by a newer synthesis revision during audio regeneration.
     */
    SUPERSEDED_REGENERATION_ASSET,

    /**
     * Media asset belonged to historical audio on a RETIRED narration segment and became unreferenced after safe assignment removal (MS-04.9H.7C1C1).
     */
    OBSOLETE_RETIRED_SEGMENT_AUDIO
}
