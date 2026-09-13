package com.universe.novel.application.voice;

import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.application.voice.dto.ManagedVoiceDTOMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class GetManagedVoiceDetailUseCase {

    private final ManagedVoiceRepositoryPort repository;

    public GetManagedVoiceDetailUseCase(
            ManagedVoiceRepositoryPort repository
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Transactional(readOnly = true)
    public ManagedVoiceDTO execute(UUID voiceId) {
        Objects.requireNonNull(voiceId, "voiceId must not be null");
        return repository.findById(voiceId)
                .map(ManagedVoiceDTOMapper::toDTO)
                .orElseThrow(() -> new ManagedVoiceNotFoundException(voiceId));
    }
}
