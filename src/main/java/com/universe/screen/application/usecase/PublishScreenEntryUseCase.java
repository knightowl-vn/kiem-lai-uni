package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.InvalidScreenTitleTotalEpisodeCountException;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoConflictException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaVisibilityPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class PublishScreenEntryUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ScreenMediaVisibilityPort mediaVisibilityPort;
    private final ClockPort clockPort;

    public PublishScreenEntryUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository,
            ScreenMediaVisibilityPort mediaVisibilityPort,
            ClockPort clockPort
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
        this.mediaVisibilityPort = Objects.requireNonNull(mediaVisibilityPort, "ScreenMediaVisibilityPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    public ScreenEntryDTO execute(UUID titleId, UUID entryId) {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");
        Objects.requireNonNull(entryId, "Entry ID cannot be null.");

        // Preliminary read of attached video to enforce global lock order
        Optional<UUID> attachedVideoOpt = entryRepository.findVideoMediaAssetIdById(entryId);
        UUID preliminaryVideoAssetId = attachedVideoOpt.orElse(null);

        // STEP 1: Global Lock Protocol - MediaAsset lock FIRST (if video attached)
        if (preliminaryVideoAssetId != null) {
            mediaVisibilityPort.lockAndValidateActiveVideoAsset(preliminaryVideoAssetId);
        }

        // STEP 2: Global Lock Protocol - ScreenTitle lock SECOND
        ScreenTitle title = titleRepository.findByIdForUpdate(titleId)
                .orElseThrow(() -> new ScreenTitleNotFoundException(titleId));

        // STEP 3: Global Lock Protocol - ScreenEntry lock THIRD
        ScreenEntry entry = entryRepository.findByIdForUpdate(entryId)
                .orElseThrow(() -> new ScreenEntryNotFoundException(entryId));

        if (!entry.getTitleId().equals(titleId)) {
            throw new ScreenEntryNotFoundException(entryId);
        }

        // STEP 4: P0-C Stale Check - Authoritative locked videoMediaAssetId must match preliminary read
        UUID authoritativeVideoAssetId = entry.getVideoMediaAssetId();
        if (!Objects.equals(preliminaryVideoAssetId, authoritativeVideoAssetId)) {
            throw new ScreenEntryVideoConflictException(
                    entryId,
                    preliminaryVideoAssetId,
                    authoritativeVideoAssetId
            );
        }

        if (authoritativeVideoAssetId != null) {
            mediaVisibilityPort.validateVideoProvenance(authoritativeVideoAssetId, entryId);
        }

        if (entry.getType() == ScreenEntryType.EPISODE && entry.getNumber() != null) {
            if (title.getStructure() == ScreenStructure.SERIES && title.getTotalEpisodeCount() != null) {
                if (entry.getNumber() > title.getTotalEpisodeCount()) {
                    throw new InvalidScreenTitleTotalEpisodeCountException(
                            "Không thể xuất bản tập " + entry.getNumber()
                                     + " vượt quá tổng số tập dự kiến (" + title.getTotalEpisodeCount() + ") của Screen Title."
                    );
                }
            }
        }

        Instant publishedAt = clockPort.now();
        entry.publish(publishedAt);

        ScreenEntry saved = entryRepository.save(entry);

        // STEP 5: P0-A Visibility reconciliation with shared-reference protection
        if (authoritativeVideoAssetId != null) {
            if (title.getStatus() == ScreenPublicationStatus.PUBLISHED) {
                mediaVisibilityPort.makeVideoPublic(authoritativeVideoAssetId, entryId);
            } else {
                if (!entryRepository.hasOtherPublishedReference(authoritativeVideoAssetId, entryId)) {
                    mediaVisibilityPort.makeVideoPrivate(authoritativeVideoAssetId, entryId);
                }
            }
        }

        return toDTO(saved);
    }

    private static ScreenEntryDTO toDTO(ScreenEntry entry) {
        return ScreenEntryDTO.from(entry);
    }
}
