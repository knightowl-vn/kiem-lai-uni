package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoAlreadyAttachedException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class AttachScreenEntryVideoUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ScreenMediaValidationPort mediaValidationPort;
    private final ClockPort clockPort;

    public AttachScreenEntryVideoUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository,
            ScreenMediaValidationPort mediaValidationPort,
            ClockPort clockPort
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
        this.mediaValidationPort = Objects.requireNonNull(mediaValidationPort, "ScreenMediaValidationPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    public ScreenEntryDTO execute(UUID titleId, UUID entryId, UUID videoMediaAssetId) {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");
        Objects.requireNonNull(entryId, "Entry ID cannot be null.");
        Objects.requireNonNull(videoMediaAssetId, "Video Media Asset ID cannot be null.");

        if (!titleRepository.existsById(titleId)) {
            throw new ScreenTitleNotFoundException(titleId);
        }

        // Step 1: Atomically lock and validate candidate video media asset
        mediaValidationPort.lockAndValidateVideoMediaAsset(videoMediaAssetId);

        // Step 2: Acquire pessimistic row-level lock on ScreenEntry
        ScreenEntry entry = entryRepository.findByIdForUpdate(entryId)
                .orElseThrow(() -> new ScreenEntryNotFoundException(entryId));

        // Step 3: Validate entry state and intent
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

        // Step 4: Mutate and save ScreenEntry
        Instant now = clockPort.now();
        entry.updateMedia(videoMediaAssetId, entry.getThumbnailMediaAssetId(), now);

        ScreenEntry saved = entryRepository.save(entry);
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
