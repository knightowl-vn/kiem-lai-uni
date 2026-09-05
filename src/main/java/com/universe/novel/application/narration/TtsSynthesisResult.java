package com.universe.novel.application.narration;

/**
 * Passive result record holding synthesized TTS audio content.
 *
 * @param audioBytes the raw binary audio bytes
 * @param mediaType  the MIME/media type of the audio (e.g. "audio/wav")
 */
public record TtsSynthesisResult(
        byte[] audioBytes,
        String mediaType
) {
}
