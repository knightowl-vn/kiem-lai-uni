package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.UpdateScreenTitleCommand;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.InvalidScreenTimelineConfigurationException;
import com.universe.screen.application.exceptions.InvalidScreenTitleTotalEpisodeCountException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleSlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.EffectiveScreenTimeline;
import com.universe.screen.domain.ScreenAiringStatus;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenIntroInterval;
import com.universe.screen.domain.ScreenOutroMarker;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTimelineTimestampFormat;
import com.universe.screen.domain.ScreenTitle;
import com.universe.shared.time.ClockPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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

        ScreenAiringStatus targetAiringStatus;
        Integer targetReleaseYear;
        String targetCountry;
        String targetOriginalAuthor;
        String targetDirector;
        String targetProductionStudio;

        if (!command.updateReleaseMetadata()) {
            targetAiringStatus = existing.getAiringStatus();
            targetReleaseYear = existing.getReleaseYear();
            targetCountry = existing.getCountry();
            targetOriginalAuthor = existing.getOriginalAuthor();
            targetDirector = existing.getDirector();
            targetProductionStudio = existing.getProductionStudio();
        } else {
            targetAiringStatus = command.airingStatus();
            targetReleaseYear = command.releaseYear();
            targetCountry = command.country();
            targetOriginalAuthor = command.originalAuthor();
            targetDirector = command.director();
            targetProductionStudio = command.productionStudio();
        }

        Long targetDefaultIntroStart;
        Long targetDefaultIntroEnd;
        Long targetDefaultOutroStart;
        Long targetDefaultOutroEnd;

        if (!command.updateDefaultTimeline()) {
            targetDefaultIntroStart = existing.getDefaultIntroStartSeconds();
            targetDefaultIntroEnd = existing.getDefaultIntroEndSeconds();
            targetDefaultOutroStart = existing.getDefaultOutroStartSeconds();
            targetDefaultOutroEnd = existing.getDefaultOutroEndSeconds();
        } else {
            targetDefaultIntroStart = command.defaultIntroStartSeconds();
            targetDefaultIntroEnd = command.defaultIntroEndSeconds();
            targetDefaultOutroStart = command.defaultOutroStartSeconds();
            targetDefaultOutroEnd = command.updateDefaultOutroEnd()
                    ? command.defaultOutroEndSeconds()
                    : existing.getDefaultOutroEndSeconds();
            validateTitleDefaultTimelineAndExistingEntries(
                    existing.getId(),
                    targetDefaultIntroStart,
                    targetDefaultIntroEnd,
                    targetDefaultOutroStart,
                    targetDefaultOutroEnd
            );
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
                targetTotalEpisodes,
                targetAiringStatus,
                targetReleaseYear,
                targetCountry,
                targetOriginalAuthor,
                targetDirector,
                targetProductionStudio,
                targetDefaultIntroStart,
                targetDefaultIntroEnd,
                targetDefaultOutroStart,
                targetDefaultOutroEnd
        );

        ScreenTitle saved = titleRepository.save(existing);

        return toDTO(saved);
    }

    private void validateTitleDefaultTimelineAndExistingEntries(
            java.util.UUID titleId,
            Long defaultIntroStartSeconds,
            Long defaultIntroEndSeconds,
            Long defaultOutroStartSeconds,
            Long defaultOutroEndSeconds
    ) {
        try {
            ScreenTimelineTimestampFormat.validateIntroPairAndOrder(defaultIntroStartSeconds, defaultIntroEndSeconds);
            ScreenTimelineTimestampFormat.validateOutroPairAndOrder(defaultOutroStartSeconds, defaultOutroEndSeconds);
            ScreenTimelineTimestampFormat.validateOutroNotBeforeIntroEnd(defaultIntroEndSeconds, defaultOutroStartSeconds);
        } catch (IllegalArgumentException ex) {
            throw new InvalidScreenTimelineConfigurationException(ex.getMessage(), ex);
        }

        ScreenIntroInterval candidateDefaultIntro = ScreenIntroInterval.ofNullable(
                defaultIntroStartSeconds,
                defaultIntroEndSeconds
        );
        ScreenOutroMarker candidateDefaultOutro = ScreenOutroMarker.ofNullable(
                defaultOutroStartSeconds,
                defaultOutroEndSeconds
        );

        if (candidateDefaultIntro == null && candidateDefaultOutro == null) {
            return;
        }

        List<ScreenEntry> entries = entryRepository.findByTitleId(titleId);
        if (entries == null || entries.isEmpty()) {
            return;
        }

        for (ScreenEntry entry : entries) {
            boolean inheritsIntro = entry.getIntroMode().isInherit() && candidateDefaultIntro != null;
            boolean inheritsOutro = entry.getOutroMode().isInherit() && candidateDefaultOutro != null;
            if (!inheritsIntro && !inheritsOutro) {
                continue;
            }

            ScreenIntroInterval effectiveIntro = switch (entry.getIntroMode()) {
                case DISABLED -> null;
                case OVERRIDE -> entry.getIntroOverride();
                case INHERIT -> candidateDefaultIntro;
            };
            ScreenOutroMarker effectiveOutro = switch (entry.getOutroMode()) {
                case DISABLED -> null;
                case OVERRIDE -> entry.getOutroOverride();
                case INHERIT -> candidateDefaultOutro;
            };

            if (effectiveIntro != null
                    && effectiveOutro != null
                    && effectiveOutro.startSeconds() < effectiveIntro.endSeconds()) {
                throw new InvalidScreenTimelineConfigurationException(
                        ScreenTimelineTimestampFormat.INVALID_OUTRO_BEFORE_INTRO_END_MESSAGE
                                + " (Xung đột với tập \"" + entry.getTitle() + "\")."
                );
            }

            Long durationSeconds = entry.getDurationSeconds();
            if (durationSeconds != null && durationSeconds > 0) {
                if (inheritsIntro && effectiveIntro.endSeconds() > durationSeconds) {
                    throw new InvalidScreenTimelineConfigurationException(
                            ScreenTimelineTimestampFormat.INTRO_EXCEEDS_DURATION_MESSAGE
                                    + " (Tập \"" + entry.getTitle() + "\": " + durationSeconds + " giây)."
                    );
                }
                if (inheritsOutro) {
                    if (effectiveOutro.startSeconds() >= durationSeconds) {
                        throw new InvalidScreenTimelineConfigurationException(
                                ScreenTimelineTimestampFormat.OUTRO_EXCEEDS_DURATION_MESSAGE
                                        + " (Tập \"" + entry.getTitle() + "\": " + durationSeconds + " giây)."
                        );
                    }
                    if (effectiveOutro.hasEnd() && effectiveOutro.endSeconds() > durationSeconds) {
                        throw new InvalidScreenTimelineConfigurationException(
                                ScreenTimelineTimestampFormat.OUTRO_END_EXCEEDS_DURATION_MESSAGE
                                        + " (Tập \"" + entry.getTitle() + "\": " + durationSeconds + " giây)."
                        );
                    }
                }
            }
        }
    }

    private static ScreenTitleDTO toDTO(ScreenTitle title) {
        return ScreenTitleDTO.from(title);
    }
}
