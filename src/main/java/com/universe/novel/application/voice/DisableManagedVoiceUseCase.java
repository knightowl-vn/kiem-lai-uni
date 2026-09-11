package com.universe.novel.application.voice;

import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.narration.PublicManagedVoiceCatalogInvalidationCoordinator;
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
public class DisableManagedVoiceUseCase {

    private final ManagedVoiceRepositoryPort repository;
    private final ClockPort clock;
    private final PublicManagedVoiceCatalogInvalidationCoordinator catalogInvalidationCoordinator;

    public DisableManagedVoiceUseCase(
            ManagedVoiceRepositoryPort repository,
            ClockPort clock,
            PublicManagedVoiceCatalogInvalidationCoordinator catalogInvalidationCoordinator
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.catalogInvalidationCoordinator = Objects.requireNonNull(
                catalogInvalidationCoordinator,
                "catalogInvalidationCoordinator must not be null"
        );
    }

    @Transactional
    public ManagedVoiceDTO execute(UUID voiceId) {
        Objects.requireNonNull(voiceId, "voiceId must not be null");

        ManagedVoice voice = repository.findById(voiceId)
                .orElseThrow(() -> new ManagedVoiceNotFoundException(voiceId));

        if (voice.isDefaultVoice()) {
            throw new ManagedVoiceInvalidStateException("Không thể vô hiệu hóa giọng đọc đang được đặt làm mặc định.");
        }

        Instant now = clock.now();
        voice.disable(now);

        ManagedVoice saved = repository.save(voice);
        catalogInvalidationCoordinator.invalidateAfterCommit();
        return ManagedVoiceDTOMapper.toDTO(saved);
    }
}
