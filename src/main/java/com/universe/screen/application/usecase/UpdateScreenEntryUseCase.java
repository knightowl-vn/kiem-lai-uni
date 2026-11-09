package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.UpdateScreenEntryCommand;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.InvalidScreenTimelineConfigurationException;
import com.universe.screen.application.exceptions.InvalidScreenTitleTotalEpisodeCountException;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntrySlugAlreadyExistsException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryCompatibility;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenIntroInterval;
import com.universe.screen.domain.ScreenOutroMarker;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTimelineMode;
import com.universe.screen.domain.ScreenTimelineTimestampFormat;
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

        ScreenTitle title = titleRepository.findByIdForUpdate(command.titleId())
                .orElseThrow(() -> new ScreenTitleNotFoundException(command.titleId()));

        ScreenEntry existing = entryRepository.findByIdForUpdate(command.entryId())
                .orElseThrow(() -> new ScreenEntryNotFoundException(command.entryId()));

        if (!existing.getTitleId().equals(command.titleId())) {
            throw new ScreenEntryNotFoundException(command.entryId());
        }

        if (command.type() == null) {
            throw new IllegalArgumentException("ScreenEntry type is required.");
        }

        ScreenEntryCompatibility.validateCompatibility(title.getStructure(), command.type());

        if (existing.getStatus() == ScreenPublicationStatus.PUBLISHED
                && command.type() == ScreenEntryType.EPISODE
                && command.number() != null
                && title.getStructure() == ScreenStructure.SERIES
                && title.getTotalEpisodeCount() != null
                && command.number() > title.getTotalEpisodeCount()) {
            throw new InvalidScreenTitleTotalEpisodeCountException(
                    "Không thể cập nhật tập đã xuất bản thành số " + command.number()
                            + " vượt quá tổng số tập dự kiến (" + title.getTotalEpisodeCount() + ") của Screen Title."
            );
        }

        String requestedSlug = command.slug() != null ? command.slug().trim().toLowerCase(Locale.ROOT) : "";
        if (!requestedSlug.equalsIgnoreCase(existing.getSlug())) {
            Optional<ScreenEntry> slugOwner = entryRepository.findByTitleIdAndSlug(command.titleId(), requestedSlug);
            if (slugOwner.isPresent() && !slugOwner.get().getId().equals(existing.getId())) {
                throw new ScreenEntrySlugAlreadyExistsException(command.slug());
            }
        }

        ScreenTimelineMode targetIntroMode;
        Long targetIntroStart;
        Long targetIntroEnd;
        ScreenTimelineMode targetOutroMode;
        Long targetOutroStart;

        if (!command.updateTimeline()) {
            targetIntroMode = existing.getIntroMode();
            targetIntroStart = existing.getIntroStartSeconds();
            targetIntroEnd = existing.getIntroEndSeconds();
            targetOutroMode = existing.getOutroMode();
            targetOutroStart = existing.getOutroStartSeconds();
        } else {
            if (command.introMode() == null || command.outroMode() == null) {
                throw new InvalidScreenTimelineConfigurationException(
                        "Vui lòng chọn chế độ mốc thời gian cho cả Intro và Outro."
                );
            }
            targetIntroMode = command.introMode();
            targetIntroStart = command.introStartSeconds();
            targetIntroEnd = command.introEndSeconds();
            targetOutroMode = command.outroMode();
            targetOutroStart = command.outroStartSeconds();
        }

        validateCandidateEntryTimeline(
                title,
                command.durationSeconds(),
                targetIntroMode,
                targetIntroStart,
                targetIntroEnd,
                targetOutroMode,
                targetOutroStart
        );

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
                now,
                targetIntroMode,
                targetIntroStart,
                targetIntroEnd,
                targetOutroMode,
                targetOutroStart
        );

        ScreenEntry saved = entryRepository.save(existing);

        return toDTO(saved);
    }

    private static void validateCandidateEntryTimeline(
            ScreenTitle title,
            Long durationSeconds,
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds
    ) {
        ScreenIntroInterval candidateIntro;
        if (introMode == ScreenTimelineMode.OVERRIDE) {
            if (introStartSeconds == null || introEndSeconds == null) {
                throw new InvalidScreenTimelineConfigurationException(
                        ScreenTimelineTimestampFormat.INCOMPLETE_INTRO_PAIR_MESSAGE
                );
            }
            try {
                ScreenTimelineTimestampFormat.validateIntroPairAndOrder(introStartSeconds, introEndSeconds);
                candidateIntro = new ScreenIntroInterval(introStartSeconds, introEndSeconds);
            } catch (IllegalArgumentException ex) {
                throw new InvalidScreenTimelineConfigurationException(ex.getMessage(), ex);
            }
        } else {
            if (introStartSeconds != null || introEndSeconds != null) {
                throw new InvalidScreenTimelineConfigurationException(
                        "Chế độ " + introMode + " không được phép lưu mốc thời gian Intro tùy chỉnh."
                );
            }
            candidateIntro = introMode == ScreenTimelineMode.INHERIT ? title.getDefaultIntroInterval() : null;
        }

        ScreenOutroMarker candidateOutro;
        if (outroMode == ScreenTimelineMode.OVERRIDE) {
            if (outroStartSeconds == null) {
                throw new InvalidScreenTimelineConfigurationException(
                        ScreenTimelineTimestampFormat.MISSING_OUTRO_OVERRIDE_MESSAGE
                );
            }
            if (outroStartSeconds < 0) {
                throw new InvalidScreenTimelineConfigurationException(
                        ScreenTimelineTimestampFormat.INVALID_TIMESTAMP_MESSAGE
                );
            }
            candidateOutro = new ScreenOutroMarker(outroStartSeconds);
        } else {
            if (outroStartSeconds != null) {
                throw new InvalidScreenTimelineConfigurationException(
                        "Chế độ " + outroMode + " không được phép lưu mốc thời gian Outro tùy chỉnh."
                );
            }
            candidateOutro = outroMode == ScreenTimelineMode.INHERIT ? title.getDefaultOutroMarker() : null;
        }

        if (candidateIntro != null
                && candidateOutro != null
                && candidateOutro.startSeconds() < candidateIntro.endSeconds()) {
            throw new InvalidScreenTimelineConfigurationException(
                    ScreenTimelineTimestampFormat.INVALID_OUTRO_BEFORE_INTRO_END_MESSAGE
            );
        }

        if (durationSeconds != null && durationSeconds > 0) {
            if (candidateIntro != null && candidateIntro.endSeconds() > durationSeconds) {
                throw new InvalidScreenTimelineConfigurationException(
                        ScreenTimelineTimestampFormat.INTRO_EXCEEDS_DURATION_MESSAGE
                );
            }
            if (candidateOutro != null && candidateOutro.startSeconds() >= durationSeconds) {
                throw new InvalidScreenTimelineConfigurationException(
                        ScreenTimelineTimestampFormat.OUTRO_EXCEEDS_DURATION_MESSAGE
                );
            }
        }
    }

    private static ScreenEntryDTO toDTO(ScreenEntry entry) {
        return ScreenEntryDTO.from(entry);
    }
}
