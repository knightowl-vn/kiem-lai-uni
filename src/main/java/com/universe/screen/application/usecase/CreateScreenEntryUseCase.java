package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.CreateScreenEntryCommand;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntrySlugAlreadyExistsException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryCompatibility;
import com.universe.screen.domain.ScreenTitle;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class CreateScreenEntryUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ClockPort clockPort;

    public CreateScreenEntryUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository,
            ClockPort clockPort
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    public ScreenEntryDTO execute(CreateScreenEntryCommand command) {
        Objects.requireNonNull(command, "CreateScreenEntryCommand cannot be null.");
        Objects.requireNonNull(command.titleId(), "Title ID cannot be null.");

        ScreenTitle title = titleRepository.findById(command.titleId())
                .orElseThrow(() -> new ScreenTitleNotFoundException(command.titleId()));

        if (command.type() == null) {
            throw new IllegalArgumentException("ScreenEntry type is required.");
        }

        ScreenEntryCompatibility.validateCompatibility(title.getStructure(), command.type());

        if (entryRepository.existsByTitleIdAndSlug(command.titleId(), command.slug())) {
            throw new ScreenEntrySlugAlreadyExistsException(command.slug());
        }

        Instant now = clockPort.now();
        UUID entryId = UUID.randomUUID();

        ScreenEntry draft = ScreenEntry.createDraft(
                entryId,
                command.titleId(),
                command.slug(),
                command.type(),
                command.number(),
                command.title(),
                command.synopsis(),
                null,
                null,
                command.durationSeconds(),
                now
        );

        ScreenEntry saved = entryRepository.save(draft);

        return toDTO(saved);
    }

    private static ScreenEntryDTO toDTO(ScreenEntry entry) {
        return ScreenEntryDTO.from(entry);
    }
}
