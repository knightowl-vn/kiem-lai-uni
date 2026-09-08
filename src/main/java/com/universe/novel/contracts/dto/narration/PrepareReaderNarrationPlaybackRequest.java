package com.universe.novel.contracts.dto.narration;

/**
 * Public request payload for reader narration playback preparation (MS-04.9H.7D1A).
 *
 * @param voiceKey unique public key of the managed voice requested for playback
 */
public record PrepareReaderNarrationPlaybackRequest(
        String voiceKey
) {
}
