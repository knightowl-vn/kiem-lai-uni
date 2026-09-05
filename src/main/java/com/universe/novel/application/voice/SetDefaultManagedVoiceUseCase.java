package com.universe.novel.application.voice;

import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
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
public class SetDefaultManagedVoiceUseCase {

    private final ManagedVoiceRepositoryPort repository;
    private final ClockPort clock;

    public SetDefaultManagedVoiceUseCase(
            ManagedVoiceRepositoryPort repository,
            ClockPort clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public ManagedVoiceDTO execute(UUID voiceId) {
        Objects.requireNonNull(voiceId, "voiceId must not be null");

        ManagedVoice target = repository.findById(voiceId)
                .orElseThrow(() -> new ManagedVoiceNotFoundException(voiceId));

        if (!target.isActive()) {
            throw new ManagedVoiceInvalidStateException("Chỉ có thể đặt giọng đọc ở trạng thái ACTIVE làm mặc định.");
        }

        Instant now = clock.now();

        repository.findDefaultVoice().ifPresent(currentDefault -> {
            if (!currentDefault.getId().equals(target.getId())) {
                currentDefault.unmarkDefault(now);
                repository.save(currentDefault);
            }
        });

        target.markDefault(now);
        ManagedVoice saved = repository.save(target);
        return ManagedVoiceDTOMapper.toDTO(saved);
    }
}
