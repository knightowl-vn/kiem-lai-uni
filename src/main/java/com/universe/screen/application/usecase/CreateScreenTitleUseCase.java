package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.CreateScreenTitleCommand;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.ScreenTitleSlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenSlugGenerator;
import com.universe.screen.domain.ScreenTitle;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class CreateScreenTitleUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenMediaValidationPort mediaValidationPort;
    private final ClockPort clockPort;
    private final IdGeneratorPort idGeneratorPort;

    @Autowired
    public CreateScreenTitleUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenMediaValidationPort mediaValidationPort,
            ClockPort clockPort,
            IdGeneratorPort idGeneratorPort
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.mediaValidationPort = Objects.requireNonNull(mediaValidationPort, "ScreenMediaValidationPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.idGeneratorPort = idGeneratorPort;
    }

    public CreateScreenTitleUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenMediaValidationPort mediaValidationPort,
            ClockPort clockPort
    ) {
        this(titleRepository, mediaValidationPort, clockPort, null);
    }

    public ScreenTitleDTO execute(CreateScreenTitleCommand command) {
        Objects.requireNonNull(command, "CreateScreenTitleCommand cannot be null.");

        String resolvedSlug;
        if (command.slug() != null && !command.slug().isBlank()) {
            resolvedSlug = command.slug().trim().toLowerCase(Locale.ROOT);
            if (titleRepository.existsBySlug(resolvedSlug)) {
                throw new ScreenTitleSlugAlreadyExistsException(resolvedSlug);
            }
        } else {
            String baseSlug = ScreenSlugGenerator.generate(command.title());
            resolvedSlug = resolveUniqueSlug(baseSlug);
        }

        mediaValidationPort.validateImageMediaAsset(command.posterMediaAssetId());
        mediaValidationPort.validateImageMediaAsset(command.backdropMediaAssetId());

        Instant now = clockPort.now();
        UUID id = (idGeneratorPort != null) ? idGeneratorPort.generate() : UUID.randomUUID();

        ScreenTitle draft = ScreenTitle.createDraft(
                id,
                resolvedSlug,
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

    private String resolveUniqueSlug(String baseSlug) {
        String candidate = baseSlug;
        int suffix = 2;
        while (titleRepository.existsBySlug(candidate)) {
            String suffixStr = "-" + suffix;
            int maxBaseLength = ScreenSlugGenerator.MAX_SLUG_LENGTH - suffixStr.length();
            String truncatedBase = baseSlug;
            if (truncatedBase.length() > maxBaseLength) {
                truncatedBase = truncatedBase.substring(0, maxBaseLength).replaceAll("-+$", "");
                if (truncatedBase.isBlank()) {
                    truncatedBase = "slug";
                }
            }
            candidate = truncatedBase + suffixStr;
            suffix++;
        }
        return candidate;
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
