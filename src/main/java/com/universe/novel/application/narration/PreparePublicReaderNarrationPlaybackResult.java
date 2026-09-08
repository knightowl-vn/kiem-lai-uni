package com.universe.novel.application.narration;

import java.util.Objects;

/**
 * Result returned from public reader playback preparation containing the internal playback result
 * paired with the public voice key (MS-04.9H.7D1A).
 *
 * @param playbackResult composite playback result from the internal preparation use case
 * @param voiceKey       public key of the managed voice
 */
public record PreparePublicReaderNarrationPlaybackResult(
        PrepareReaderNarrationPlaybackResult playbackResult,
        String voiceKey
) {
    public PreparePublicReaderNarrationPlaybackResult {
        Objects.requireNonNull(playbackResult, "playbackResult must not be null");
        if (voiceKey == null || voiceKey.isBlank()) {
            throw new IllegalArgumentException("voiceKey must not be null or blank");
        }
    }

    /**
     * Returns true if audio for the requested segment is available for immediate playback.
     */
    public boolean isPlayableNow() {
        return playbackResult.isPlayableNow();
    }

    /**
     * Returns true if audio for the requested segment is not available and playback is blocked.
     */
    public boolean blocksPlayback() {
        return playbackResult.blocksPlayback();
    }
}
