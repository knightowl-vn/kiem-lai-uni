package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoConflictException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaVisibilityPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ArchiveScreenEntryUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ScreenMediaVisibilityPort mediaVisibilityPort;
    private final ClockPort clockPort;

    public ArchiveScreenEntryUseCase(
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

        if (!titleRepository.existsById(titleId)) {
            throw new ScreenTitleNotFoundException(titleId);
        }

        // Preliminary read of attached video to respect global lock order
        Optional<UUID> attachedVideoOpt = entryRepository.findVideoMediaAssetIdById(entryId);
        UUID preliminaryVideoAssetId = attachedVideoOpt.orElse(null);

        // STEP 1: Global Lock Protocol - MediaAsset lock FIRST (if video attached)
        if (preliminaryVideoAssetId != null) {
            mediaVisibilityPort.lockAndInspectVideoMediaAsset(preliminaryVideoAssetId);
        }

        // STEP 2: Global Lock Protocol - ScreenEntry lock SECOND
        ScreenEntry entry = entryRepository.findByIdForUpdate(entryId)
                .orElseThrow(() -> new ScreenEntryNotFoundException(entryId));

        if (!entry.getTitleId().equals(titleId)) {
            throw new ScreenEntryNotFoundException(entryId);
        }

        // STEP 3: P0-C Stale Check - Authoritative locked videoMediaAssetId must match preliminary read
        UUID authoritativeVideoAssetId = entry.getVideoMediaAssetId();
        if (!Objects.equals(preliminaryVideoAssetId, authoritativeVideoAssetId)) {
            throw new ScreenEntryVideoConflictException(
                    entryId,
                    preliminaryVideoAssetId,
                    authoritativeVideoAssetId
            );
        }

        Instant archivedAt = clockPort.now();
        entry.archive(archivedAt);

        ScreenEntry saved = entryRepository.save(entry);

        // STEP 4: P0-A Demote attached video to PRIVATE ONLY IF no other legitimate published reference exists
        if (authoritativeVideoAssetId != null) {
            boolean hasOther = entryRepository.hasOtherPublishedReference(authoritativeVideoAssetId, entryId);
            if (!hasOther) {
                mediaVisibilityPort.makeVideoPrivate(authoritativeVideoAssetId, entryId);
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
