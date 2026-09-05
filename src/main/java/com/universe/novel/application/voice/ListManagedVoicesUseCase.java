package com.universe.novel.application.voice;

import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.application.voice.dto.ManagedVoiceDTOMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class ListManagedVoicesUseCase {

    private final ManagedVoiceRepositoryPort repository;

    public ListManagedVoicesUseCase(
            ManagedVoiceRepositoryPort repository
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Transactional(readOnly = true)
    public List<ManagedVoiceDTO> execute() {
        return repository.findAll().stream()
                .map(ManagedVoiceDTOMapper::toDTO)
                .toList();
    }
}
