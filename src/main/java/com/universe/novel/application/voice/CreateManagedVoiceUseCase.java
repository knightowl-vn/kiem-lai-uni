package com.universe.novel.application.voice;

import com.universe.novel.application.exceptions.ManagedVoiceKeyAlreadyExistsException;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.voice.commands.CreateManagedVoiceCommand;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.application.voice.dto.ManagedVoiceDTOMapper;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class CreateManagedVoiceUseCase {

    private final ManagedVoiceRepositoryPort repository;
    private final IdGeneratorPort idGenerator;
    private final ClockPort clock;

    public CreateManagedVoiceUseCase(
            ManagedVoiceRepositoryPort repository,
            IdGeneratorPort idGenerator,
            ClockPort clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public ManagedVoiceDTO execute(CreateManagedVoiceCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        if (repository.existsByVoiceKey(command.voiceKey())) {
            throw new ManagedVoiceKeyAlreadyExistsException(command.voiceKey());
        }

        UUID id = idGenerator.generate();
        Instant now = clock.now();

        if (command.defaultVoice()) {
            repository.findDefaultVoice().ifPresent(currentDefault -> {
                currentDefault.unmarkDefault(now);
                repository.save(currentDefault);
            });
        }

        ManagedVoice voice = ManagedVoice.create(
                id,
                command.voiceKey(),
                command.displayName(),
                command.providerVoiceId(),
                command.displayOrder(),
                command.defaultVoice(),
                now
        );

        ManagedVoice saved = repository.save(voice);
        return ManagedVoiceDTOMapper.toDTO(saved);
    }
}
