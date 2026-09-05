package com.universe.novel.application.voice;

import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.application.voice.dto.ManagedVoiceDTOMapper;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class ActivateManagedVoiceUseCase {

    private final ManagedVoiceRepositoryPort repository;
    private final ClockPort clock;

    public ActivateManagedVoiceUseCase(
            ManagedVoiceRepositoryPort repository,
            ClockPort clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public ManagedVoiceDTO execute(UUID voiceId) {
        Objects.requireNonNull(voiceId, "voiceId must not be null");

        ManagedVoice voice = repository.findById(voiceId)
                .orElseThrow(() -> new ManagedVoiceNotFoundException(voiceId));

        Instant now = clock.now();
        voice.activate(now);

        ManagedVoice saved = repository.save(voice);
        return ManagedVoiceDTOMapper.toDTO(saved);
    }
}
