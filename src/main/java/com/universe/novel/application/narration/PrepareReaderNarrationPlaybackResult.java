package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Composite result returned to the reader front-door containing immediate segment preparation
 * along with background continuation dispatch status (MS-04.9H.7C2C3B).
 *
 * @param immediateResult            result of the synchronous requested segment preparation (non-null)
 * @param continuationDispatchStatus status of background continuation scheduling (non-null)
 */
public record PrepareReaderNarrationPlaybackResult(
        PrepareReaderNarrationSegmentResult immediateResult,
        ReaderNarrationContinuationDispatchStatus continuationDispatchStatus
) {
    public PrepareReaderNarrationPlaybackResult {
        Objects.requireNonNull(immediateResult, "immediateResult must not be null");
        Objects.requireNonNull(continuationDispatchStatus, "continuationDispatchStatus must not be null");
    }

    /**
     * Returns true if audio for the requested segment is available for immediate playback.
     */
    public boolean isPlayableNow() {
        return immediateResult.isPlayableNow();
    }

    /**
     * Returns true if audio for the requested segment is not available and playback is blocked.
     */
    public boolean blocksPlayback() {
        return immediateResult.blocksPlayback();
    }

    /**
     * Identifier of the playable media asset for the requested segment (nullable if not playable).
     */
    public UUID mediaAssetId() {
        return immediateResult.mediaAssetId();
    }
}
