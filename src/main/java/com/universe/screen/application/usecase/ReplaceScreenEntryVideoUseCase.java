package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoConflictException;
import com.universe.screen.application.exceptions.ScreenEntryVideoNotAttachedException;
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

/**
 * Use case to safely and atomically replace an attached video on a ScreenEntry.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Title/Entry relationship validation.</li>
 *   <li>Pessimistic row-level lock on ScreenEntry to prevent concurrent overwrites.</li>
 *   <li>Stale expected current video check to protect against lost updates.</li>
 *   <li>Validation of the new MediaAsset (must exist, not be DELETED, and be type VIDEO).</li>
 *   <li>Atomically swaps scalar videoMediaAssetId under Screen persistence boundary.</li>
 *   <li>The superseded MediaAsset remains completely intact in Media/B2.</li>
 *   <li>Strictly preserves publication status (including PUBLISHED) and publishedAt timestamps.</li>
 * </ul>
 */
@Service
@Transactional
public class ReplaceScreenEntryVideoUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ScreenMediaValidationPort mediaValidationPort;
    private final ClockPort clockPort;

    public ReplaceScreenEntryVideoUseCase(
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

        // Step 1: Atomically lock and validate candidate new video media asset
        mediaValidationPort.lockAndValidateVideoMediaAsset(newVideoMediaAssetId);

        // Step 2: Acquire pessimistic row-level lock on ScreenEntry
        ScreenEntry entry = entryRepository.findByIdForUpdate(entryId)
                .orElseThrow(() -> new ScreenEntryNotFoundException(entryId));

        // Step 3: Validate entry state and intent
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

        // Step 4: Mutate and save ScreenEntry
        Instant now = clockPort.now();
        entry.replaceVideo(newVideoMediaAssetId, now);

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
