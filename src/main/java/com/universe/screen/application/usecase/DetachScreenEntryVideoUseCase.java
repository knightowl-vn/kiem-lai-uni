package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoConflictException;
import com.universe.screen.application.exceptions.ScreenEntryVideoNotAttachedException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case to safely detach an attached video from a ScreenEntry.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Title/Entry relationship validation.</li>
 *   <li>Entry must have an attached video.</li>
 *   <li>Stale expected current video check to protect against concurrent modifications.</li>
 *   <li>Publication invariant: Cannot detach video from a PUBLISHED entry (entry must be ARCHIVED or replaced).</li>
 *   <li>Preserves publication timestamps, thumbnail, duration, and all other metadata.</li>
 *   <li>Does NOT delete or modify the MediaAsset or its storage objects.</li>
 * </ul>
 */
@Service
@Transactional
public class DetachScreenEntryVideoUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ClockPort clockPort;

    public DetachScreenEntryVideoUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository,
            ClockPort clockPort
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    public ScreenEntryDTO execute(UUID titleId, UUID entryId, UUID expectedVideoMediaAssetId) {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");
        Objects.requireNonNull(entryId, "Entry ID cannot be null.");
        Objects.requireNonNull(expectedVideoMediaAssetId, "Expected video MediaAsset ID cannot be null.");

        if (!titleRepository.existsById(titleId)) {
            throw new ScreenTitleNotFoundException(titleId);
        }

        ScreenEntry entry = entryRepository.findByIdForUpdate(entryId)
                .orElseThrow(() -> new ScreenEntryNotFoundException(entryId));

        if (!entry.getTitleId().equals(titleId)) {
            throw new ScreenEntryNotFoundException(entryId);
        }

        if (entry.getVideoMediaAssetId() == null) {
            throw new ScreenEntryVideoNotAttachedException(entryId);
        }

        if (!entry.getVideoMediaAssetId().equals(expectedVideoMediaAssetId)) {
            throw new ScreenEntryVideoConflictException(entryId, expectedVideoMediaAssetId, entry.getVideoMediaAssetId());
        }

        if (entry.getStatus() == ScreenPublicationStatus.PUBLISHED) {
            throw new IllegalStateException("Không thể gỡ bỏ video khỏi Screen Entry đang ở trạng thái PUBLISHED. Vui lòng lưu trữ (Archive) entry hoặc sử dụng tính năng thay thế (Replace) video.");
        }

        Instant now = clockPort.now();
        entry.detachVideo(now);

        ScreenEntry saved = entryRepository.save(entry);
        return toDTO(saved);
    }

    private static ScreenEntryDTO toDTO(ScreenEntry entry) {
        return ScreenEntryDTO.from(entry);
    }
}
