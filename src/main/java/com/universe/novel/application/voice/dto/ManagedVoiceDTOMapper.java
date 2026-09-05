package com.universe.novel.application.voice.dto;

import com.universe.novel.domain.narration.ManagedVoice;

public final class ManagedVoiceDTOMapper {

    private ManagedVoiceDTOMapper() {
    }

    public static ManagedVoiceDTO toDTO(ManagedVoice voice) {
        if (voice == null) {
            return null;
        }
        return new ManagedVoiceDTO(
                voice.getId(),
                voice.getVoiceKey(),
                voice.getDisplayName(),
                voice.getProviderVoiceId(),
                voice.getStatus().name(),
                voice.getDisplayOrder(),
                voice.isDefaultVoice(),
                voice.getSynthesisRevision(),
                voice.getCreatedAt(),
                voice.getUpdatedAt()
        );
    }
}
