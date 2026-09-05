package com.universe.novel.application.narration;

/**
 * Passive record representing a voice exposed by a TTS provider.
 *
 * @param voiceId unique identifier assigned by the external provider
 * @param label   display label of the voice
 */
public record TtsProviderVoice(
        String voiceId,
        String label
) {
}
