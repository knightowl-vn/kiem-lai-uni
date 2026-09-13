package com.universe.novel.application.ports;

import com.universe.novel.domain.narration.ManagedVoiceStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * Lightweight internal voice lookup used only by public chapter playback reads.
 */
public interface PlaybackManagedVoiceQueryPort {

    Optional<PlaybackManagedVoice> findByVoiceKey(String voiceKey);

    Optional<PlaybackManagedVoice> findPreferredActiveVoice();

    record PlaybackManagedVoice(
            UUID id,
            String voiceKey,
            ManagedVoiceStatus status,
            long synthesisRevision
    ) {
        public boolean isActive() {
            return status == ManagedVoiceStatus.ACTIVE;
        }
    }
}
