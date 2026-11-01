package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.UpdateScreenTitleCommand;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.InvalidScreenTitleTotalEpisodeCountException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleSlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import com.universe.shared.time.ClockPort;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ScreenEntryRepositoryPort entryRepository;

    @Autowired
    public UpdateScreenTitleUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenMediaValidationPort mediaValidationPort,
            ClockPort clockPort,
            ScreenEntryRepositoryPort entryRepository
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.mediaValidationPort = Objects.requireNonNull(mediaValidationPort, "ScreenMediaValidationPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
    }

    public ScreenTitleDTO execute(UpdateScreenTitleCommand command) {
        Objects.requireNonNull(command, "UpdateScreenTitleCommand cannot be null.");
        Objects.requireNonNull(command.titleId(), "Title ID cannot be null.");

        ScreenTitle existing = titleRepository.findByIdForUpdate(command.titleId())
                .orElseThrow(() -> new ScreenTitleNotFoundException(command.titleId()));

        String targetSlug = (command.slug() != null && !command.slug().isBlank())
                ? command.slug().trim().toLowerCase(Locale.ROOT)
                : existing.getSlug();

        if (!targetSlug.equalsIgnoreCase(existing.getSlug())) {
            Optional<ScreenTitle> slugOwner = titleRepository.findBySlug(targetSlug);
            if (slugOwner.isPresent() && !slugOwner.get().getId().equals(existing.getId())) {
                throw new ScreenTitleSlugAlreadyExistsException(targetSlug);
            }
        }

        Integer targetTotalEpisodes;
        if (!command.updateTotalEpisodeCount()) {
            targetTotalEpisodes = (command.structure() == ScreenStructure.FEATURE) ? null : existing.getTotalEpisodeCount();
        } else {
            targetTotalEpisodes = (command.structure() == ScreenStructure.FEATURE) ? null : command.totalEpisodeCount();
        }

        if (targetTotalEpisodes != null && command.structure() == ScreenStructure.SERIES) {
            Integer latestEp = entryRepository.findLatestPublishedEpisodeNumberByTitleId(existing.getId());
            if (latestEp != null && targetTotalEpisodes < latestEp) {
                throw new InvalidScreenTitleTotalEpisodeCountException(targetTotalEpisodes, latestEp);
            }
        }

        mediaValidationPort.validateImageMediaAsset(command.posterMediaAssetId());
        mediaValidationPort.validateImageMediaAsset(command.backdropMediaAssetId());

        Instant now = clockPort.now();

        existing.updateDetails(
                targetSlug,
                command.title(),
                command.originalTitle(),
                command.synopsis(),
                command.structure(),
                command.presentation(),
                command.posterMediaAssetId(),
                command.backdropMediaAssetId(),
                now,
                targetTotalEpisodes
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
                title.getUpdatedAt(),
                title.getTotalEpisodeCount()
        );
    }
}
