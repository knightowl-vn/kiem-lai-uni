package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.UpdateScreenEntryCommand;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
public class UpdateScreenEntryUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ClockPort clockPort;

    public UpdateScreenEntryUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository,
            ClockPort clockPort
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    public ScreenEntryDTO execute(UpdateScreenEntryCommand command) {
        Objects.requireNonNull(command, "UpdateScreenEntryCommand cannot be null.");
        Objects.requireNonNull(command.titleId(), "Title ID cannot be null.");
        Objects.requireNonNull(command.entryId(), "Entry ID cannot be null.");

        ScreenTitle title = titleRepository.findById(command.titleId())
                .orElseThrow(() -> new ScreenTitleNotFoundException(command.titleId()));

        ScreenEntry existing = entryRepository.findById(command.entryId())
                .orElseThrow(() -> new ScreenEntryNotFoundException(command.entryId()));

        if (!existing.getTitleId().equals(command.titleId())) {
            throw new ScreenEntryNotFoundException(command.entryId());
        }

        if (command.type() == null) {
            throw new IllegalArgumentException("ScreenEntry type is required.");
        }

        ScreenEntryCompatibility.validateCompatibility(title.getStructure(), command.type());

        String requestedSlug = command.slug() != null ? command.slug().trim().toLowerCase(Locale.ROOT) : "";
        if (!requestedSlug.equalsIgnoreCase(existing.getSlug())) {
            Optional<ScreenEntry> slugOwner = entryRepository.findByTitleIdAndSlug(command.titleId(), requestedSlug);
            if (slugOwner.isPresent() && !slugOwner.get().getId().equals(existing.getId())) {
                throw new ScreenEntrySlugAlreadyExistsException(command.slug());
            }
        }

        Instant now = clockPort.now();

        existing.updateDetails(
                command.slug(),
                command.type(),
                command.number(),
                command.title(),
                command.synopsis(),
                command.durationSeconds(),
                existing.getVideoMediaAssetId(),
                existing.getThumbnailMediaAssetId(),
                now
        );

        ScreenEntry saved = entryRepository.save(existing);

        return toDTO(saved);
    }

    private static ScreenEntryDTO toDTO(ScreenEntry entry) {
        return new ScreenEntryDTO(
                entry.getId(),
                entry.getTitleId(),
                entry.getSlug(),
                entry.getType(),
                entry.getNumber(),
                entry.getTitle(),
                entry.getSynopsis(),
                entry.getVideoMediaAssetId(),
                entry.getThumbnailMediaAssetId(),
                entry.getDurationSeconds(),
                entry.getStatus(),
                entry.getPublishedAt(),
                entry.getCreatedAt(),
                entry.getUpdatedAt()
        );
    }
}
