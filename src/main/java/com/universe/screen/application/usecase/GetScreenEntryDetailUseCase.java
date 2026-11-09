package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class GetScreenEntryDetailUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;

    public GetScreenEntryDetailUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
    }

    public ScreenEntryDTO execute(UUID titleId, UUID entryId) {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");
        Objects.requireNonNull(entryId, "Entry ID cannot be null.");

        if (!titleRepository.existsById(titleId)) {
            throw new ScreenTitleNotFoundException(titleId);
        }

        ScreenEntry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new ScreenEntryNotFoundException(entryId));

        if (!entry.getTitleId().equals(titleId)) {
            throw new ScreenEntryNotFoundException(entryId);
        }

        return toDTO(entry);
    }

    private static ScreenEntryDTO toDTO(ScreenEntry entry) {
        return ScreenEntryDTO.from(entry);
    }
}
