package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.UpdateScreenTitleCommand;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleSlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
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
public class UpdateScreenTitleUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenMediaValidationPort mediaValidationPort;
    private final ClockPort clockPort;

    public UpdateScreenTitleUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenMediaValidationPort mediaValidationPort,
            ClockPort clockPort
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.mediaValidationPort = Objects.requireNonNull(mediaValidationPort, "ScreenMediaValidationPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    public ScreenTitleDTO execute(UpdateScreenTitleCommand command) {
        Objects.requireNonNull(command, "UpdateScreenTitleCommand cannot be null.");
        Objects.requireNonNull(command.titleId(), "Title ID cannot be null.");

        ScreenTitle existing = titleRepository.findById(command.titleId())
                .orElseThrow(() -> new ScreenTitleNotFoundException(command.titleId()));

        String requestedSlug = command.slug() != null ? command.slug().trim().toLowerCase(Locale.ROOT) : "";
        if (!requestedSlug.equalsIgnoreCase(existing.getSlug())) {
            Optional<ScreenTitle> slugOwner = titleRepository.findBySlug(requestedSlug);
            if (slugOwner.isPresent() && !slugOwner.get().getId().equals(existing.getId())) {
                throw new ScreenTitleSlugAlreadyExistsException(command.slug());
            }
        }

        mediaValidationPort.validateImageMediaAsset(command.posterMediaAssetId());
        mediaValidationPort.validateImageMediaAsset(command.backdropMediaAssetId());

        Instant now = clockPort.now();

        existing.updateDetails(
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

        ScreenTitle saved = titleRepository.save(existing);

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
