package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoAlreadyAttachedException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenMediaVisibilityPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenPublicationStatus;
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
public class AttachScreenEntryVideoUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ScreenMediaValidationPort mediaValidationPort;
    private final ScreenMediaVisibilityPort mediaVisibilityPort;
    private final ClockPort clockPort;

    public AttachScreenEntryVideoUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository,
            ScreenMediaValidationPort mediaValidationPort,
            ScreenMediaVisibilityPort mediaVisibilityPort,
            ClockPort clockPort
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
        this.mediaValidationPort = Objects.requireNonNull(mediaValidationPort, "ScreenMediaValidationPort cannot be null.");
        this.mediaVisibilityPort = Objects.requireNonNull(mediaVisibilityPort, "ScreenMediaVisibilityPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    public ScreenEntryDTO execute(UUID titleId, UUID entryId, UUID videoMediaAssetId) {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");
        Objects.requireNonNull(entryId, "Entry ID cannot be null.");
        Objects.requireNonNull(videoMediaAssetId, "Video Media Asset ID cannot be null.");

        if (!titleRepository.existsById(titleId)) {
            throw new ScreenTitleNotFoundException(titleId);
        }

        // STEP 1: Global Lock Protocol - MediaAsset lock FIRST
        mediaValidationPort.lockAndValidateVideoMediaAsset(videoMediaAssetId);
        mediaVisibilityPort.lockAndValidateActiveVideoAsset(videoMediaAssetId);
        mediaVisibilityPort.validateVideoProvenance(videoMediaAssetId, entryId);

        // STEP 2: Global Lock Protocol - ScreenTitle lock SECOND
        ScreenTitle title = titleRepository.findByIdForUpdate(titleId)
                .orElseThrow(() -> new ScreenTitleNotFoundException(titleId));

        // STEP 3: Global Lock Protocol - ScreenEntry lock THIRD
        ScreenEntry entry = entryRepository.findByIdForUpdate(entryId)
                .orElseThrow(() -> new ScreenEntryNotFoundException(entryId));

        if (!entry.getTitleId().equals(titleId)) {
            throw new ScreenEntryNotFoundException(entryId);
        }

        // Idempotent retry: if already attached to identical video asset, return without error
        if (videoMediaAssetId.equals(entry.getVideoMediaAssetId())) {
            return toDTO(entry);
        }

        // Reject silent replacement/overwrite in V1
        if (entry.getVideoMediaAssetId() != null) {
            throw new ScreenEntryVideoAlreadyAttachedException(entryId, entry.getVideoMediaAssetId());
        }

        // STEP 4: Mutate and save ScreenEntry
        Instant now = clockPort.now();
        entry.updateMedia(videoMediaAssetId, entry.getThumbnailMediaAssetId(), now);

        ScreenEntry saved = entryRepository.save(entry);

        // STEP 5: P0-A Visibility reconciliation with shared-reference protection
        if (saved.getStatus() == ScreenPublicationStatus.PUBLISHED
                && title.getStatus() == ScreenPublicationStatus.PUBLISHED) {
            mediaVisibilityPort.makeVideoPublic(videoMediaAssetId, entryId);
        } else {
            if (!entryRepository.hasOtherPublishedReference(videoMediaAssetId, entryId)) {
                mediaVisibilityPort.makeVideoPrivate(videoMediaAssetId, entryId);
            }
        }

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
