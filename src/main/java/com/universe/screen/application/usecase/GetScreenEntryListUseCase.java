package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class GetScreenEntryListUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;

    public GetScreenEntryListUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
    }

    public List<ScreenEntryDTO> execute(UUID titleId) {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");

        if (!titleRepository.existsById(titleId)) {
            throw new ScreenTitleNotFoundException(titleId);
        }

        return entryRepository.findByTitleId(titleId)
                .stream()
                .map(GetScreenEntryListUseCase::toDTO)
                .toList();
    }

    static ScreenEntryDTO toDTO(ScreenEntry entry) {
        return ScreenEntryDTO.from(entry);
    }
}
