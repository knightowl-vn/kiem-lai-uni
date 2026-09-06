package com.universe.novel.contracts.dto.narration;

/**
 * Public, playback-safe DTO representing an available managed voice for novel narration (MS-04.9H.7A).
 */
public record PublicNarrationVoiceDTO(
        String voiceKey,
        String displayName,
        boolean defaultVoice
) {
}
