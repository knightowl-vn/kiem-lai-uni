package com.universe.novel.application.voice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Passive DTO representing a managed voice record for application and presentation consumers.
 */
public record ManagedVoiceDTO(
        UUID id,
        String voiceKey,
        String displayName,
        String providerVoiceId,
        String status,
        int displayOrder,
        boolean defaultVoice,
        long synthesisRevision,
        Instant createdAt,
        Instant updatedAt
) {
}
