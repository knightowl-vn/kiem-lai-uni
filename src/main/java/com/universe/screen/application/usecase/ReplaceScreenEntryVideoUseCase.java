package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoConflictException;
import com.universe.screen.application.exceptions.ScreenEntryVideoNotAttachedException;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Use case to safely and atomically replace an attached video on a ScreenEntry.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Title/Entry relationship validation.</li>
 *   <li>Global Lock Protocol: MediaAssets locked first in sorted UUID order, ScreenEntry second.</li>
 *   <li>Stale expected current video check to protect against lost updates.</li>
 *   <li>Validation of the new MediaAsset (must exist, not be DELETED, and be type VIDEO with canonical video/mp4 MIME).</li>
 *   <li>Atomically swaps scalar videoMediaAssetId under Screen persistence boundary.</li>
 *   <li>Shared-reference visibility protection: incoming video remains PUBLIC if shared; superseded video demoted only if unreferenced.</li>
 *   <li>Strictly preserves publication status (including PUBLISHED) and publishedAt timestamps.</li>
 * </ul>
 */
@Service
@Transactional
public class ReplaceScreenEntryVideoUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ScreenMediaValidationPort mediaValidationPort;
    private final ScreenMediaVisibilityPort mediaVisibilityPort;
    private final ClockPort clockPort;

    public ReplaceScreenEntryVideoUseCase(
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

    public ScreenEntryDTO execute(
            UUID titleId,
            UUID entryId,
            UUID newVideoMediaAssetId,
            UUID expectedCurrentVideoMediaAssetId
    ) {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");
        Objects.requireNonNull(entryId, "Entry ID cannot be null.");
        Objects.requireNonNull(newVideoMediaAssetId, "New Video Media Asset ID cannot be null.");
        Objects.requireNonNull(expectedCurrentVideoMediaAssetId, "Expected current video MediaAsset ID cannot be null.");

        if (!titleRepository.existsById(titleId)) {
            throw new ScreenTitleNotFoundException(titleId);
        }

        // STEP 1: Global Lock Protocol - MediaAssets locked FIRST in ascending UUID order
        List<UUID> orderedAssetIds = Stream.of(newVideoMediaAssetId, expectedCurrentVideoMediaAssetId)
                .distinct()
                .sorted()
                .toList();

        for (UUID assetId : orderedAssetIds) {
            mediaVisibilityPort.lockAndInspectVideoMediaAsset(assetId);
        }

        mediaValidationPort.lockAndValidateVideoMediaAsset(newVideoMediaAssetId);
        mediaVisibilityPort.validateVideoProvenance(newVideoMediaAssetId, entryId);

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
        if (newVideoMediaAssetId.equals(entry.getVideoMediaAssetId())) {
            return toDTO(entry);
        }

        if (entry.getVideoMediaAssetId() == null) {
            throw new ScreenEntryVideoNotAttachedException(entryId);
        }

        if (!entry.getVideoMediaAssetId().equals(expectedCurrentVideoMediaAssetId)) {
            throw new ScreenEntryVideoConflictException(
                    entryId,
                    expectedCurrentVideoMediaAssetId,
                    entry.getVideoMediaAssetId()
            );
        }

        // STEP 4: Mutate and save ScreenEntry
        Instant now = clockPort.now();
        entry.replaceVideo(newVideoMediaAssetId, now);

        ScreenEntry saved = entryRepository.save(entry);

        // STEP 5: P0-A Visibility reconciliation with shared-reference protection
        if (saved.getStatus() == ScreenPublicationStatus.PUBLISHED
                && title.getStatus() == ScreenPublicationStatus.PUBLISHED) {
            mediaVisibilityPort.makeVideoPublic(newVideoMediaAssetId, entryId);
        } else {
            if (!entryRepository.hasOtherPublishedReference(newVideoMediaAssetId, entryId)) {
                mediaVisibilityPort.makeVideoPrivate(newVideoMediaAssetId, entryId);
            }
        }

        // Demote superseded video to PRIVATE only if no other published reference remains
        boolean hasOtherSuperseded = entryRepository.hasOtherPublishedReference(expectedCurrentVideoMediaAssetId, entryId);
        if (!hasOtherSuperseded) {
            mediaVisibilityPort.makeVideoPrivate(expectedCurrentVideoMediaAssetId, entryId);
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
