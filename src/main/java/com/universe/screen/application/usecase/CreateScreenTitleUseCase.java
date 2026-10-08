package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.CreateScreenTitleCommand;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.ScreenTitleSlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenTitle;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class CreateScreenTitleUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenMediaValidationPort mediaValidationPort;
    private final ClockPort clockPort;

    public CreateScreenTitleUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenMediaValidationPort mediaValidationPort,
            ClockPort clockPort
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.mediaValidationPort = Objects.requireNonNull(mediaValidationPort, "ScreenMediaValidationPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    public ScreenTitleDTO execute(CreateScreenTitleCommand command) {
        Objects.requireNonNull(command, "CreateScreenTitleCommand cannot be null.");

        if (titleRepository.existsBySlug(command.slug())) {
            throw new ScreenTitleSlugAlreadyExistsException(command.slug());
        }

        mediaValidationPort.validateImageMediaAsset(command.posterMediaAssetId());
        mediaValidationPort.validateImageMediaAsset(command.backdropMediaAssetId());

        Instant now = clockPort.now();
        UUID id = UUID.randomUUID();

        ScreenTitle draft = ScreenTitle.createDraft(
                id,
                command.slug(),
                command.title(),
                command.originalTitle(),
                command.synopsis(),
                command.structure(),
                command.presentation(),
                command.posterMediaAssetId(),
                command.backdropMediaAssetId(),
                now
        );

        ScreenTitle saved = titleRepository.save(draft);

        return toDTO(saved);
    }

    private static ScreenTitleDTO toDTO(ScreenTitle title) {
        return new ScreenTitleDTO(
                title.getId(),
                title.getSlug(),
                title.getTitle(),
                title.getOriginalTitle(),
                title.getSynopsis(),
                title.getStructure(),
                title.getPresentation(),
                title.getPosterMediaAssetId(),
                title.getBackdropMediaAssetId(),
                title.getStatus(),
                title.getPublishedAt(),
                title.getCreatedAt(),
                title.getUpdatedAt()
        );
    }
}
