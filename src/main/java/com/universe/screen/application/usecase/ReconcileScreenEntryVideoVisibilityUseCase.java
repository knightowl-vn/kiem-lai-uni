package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ReconcileScreenVideoVisibilityResult;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoConflictException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaVisibilityPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Admin-authorized explicit reconciliation use case for Screen video visibility.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Title and entry existence and relational binding validation before assessing attached video.</li>
 *   <li>Strict lock hierarchy: MediaAsset lock FIRST, ScreenTitle lock SECOND, ScreenEntry lock THIRD.</li>
 *   <li>Authoritative stale-ID verification under row locks to eliminate read-then-act races.</li>
 *   <li>Shared-reference preservation: never demotes an asset while another published Screen entry references it.</li>
 *   <li>Zero republishing mutations: preserves historical publishedAt timestamps and episode numbering.</li>
 * </ul>
 */
@Service
@Transactional
public class ReconcileScreenEntryVideoVisibilityUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ScreenMediaVisibilityPort mediaVisibilityPort;

    public ReconcileScreenEntryVideoVisibilityUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository,
            ScreenMediaVisibilityPort mediaVisibilityPort
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
        this.mediaVisibilityPort = Objects.requireNonNull(mediaVisibilityPort, "ScreenMediaVisibilityPort cannot be null.");
    }

    public ReconcileScreenVideoVisibilityResult execute(UUID titleId, UUID entryId) {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");
        Objects.requireNonNull(entryId, "Entry ID cannot be null.");

        // STEP 0: Validate Title and Entry existence and mutual binding before assessing video
        if (!titleRepository.existsById(titleId)) {
            throw new ScreenTitleNotFoundException(titleId);
        }

        ScreenEntry preliminaryEntry = entryRepository.findById(entryId)
                .orElseThrow(() -> new ScreenEntryNotFoundException(entryId));

        if (!preliminaryEntry.getTitleId().equals(titleId)) {
            throw new ScreenEntryNotFoundException(entryId);
        }

        UUID videoMediaAssetId = preliminaryEntry.getVideoMediaAssetId();
        if (videoMediaAssetId == null) {
            return new ReconcileScreenVideoVisibilityResult(
                    titleId,
                    entryId,
                    null,
                    null,
                    null,
                    ReconcileScreenVideoVisibilityResult.Outcome.NO_VIDEO_ATTACHED,
                    "ScreenEntry has no attached video asset to reconcile."
            );
        }

        // STEP 1: Global Lock Protocol - MediaAsset lock FIRST
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

        // STEP 4: P0-C Stale Check - Authoritative locked videoMediaAssetId must match locked asset
        if (!videoMediaAssetId.equals(entry.getVideoMediaAssetId())) {
            throw new ScreenEntryVideoConflictException(entryId, videoMediaAssetId, entry.getVideoMediaAssetId());
        }

        // STEP 5: Reconcile visibility according to publication status and shared-reference rules
        boolean isCurrentPublished = entry.getStatus() == ScreenPublicationStatus.PUBLISHED
                && title.getStatus() == ScreenPublicationStatus.PUBLISHED;

        if (isCurrentPublished) {
            mediaVisibilityPort.makeVideoPublic(videoMediaAssetId, entryId);
            return new ReconcileScreenVideoVisibilityResult(
                    titleId,
                    entryId,
                    videoMediaAssetId,
                    null,
                    "PUBLIC",
                    ReconcileScreenVideoVisibilityResult.Outcome.RECONCILED,
                    "Screen video media asset successfully reconciled to PUBLIC."
            );
        }

        // Current entry or title is not published. Check for valid shared published references elsewhere.
        boolean hasOtherPublishedRef = entryRepository.hasOtherPublishedReference(videoMediaAssetId, entryId);
        if (hasOtherPublishedRef) {
            return new ReconcileScreenVideoVisibilityResult(
                    titleId,
                    entryId,
                    videoMediaAssetId,
                    null,
                    "PUBLIC",
                    ReconcileScreenVideoVisibilityResult.Outcome.KEPT_PUBLIC_BY_SHARED_REFERENCE,
                    "ScreenEntry or ScreenTitle is not in PUBLISHED status, but video visibility remains PUBLIC due to another active published reference."
            );
        }

        // No other valid published reference exists: demote to PRIVATE
        mediaVisibilityPort.makeVideoPrivate(videoMediaAssetId, entryId);
        return new ReconcileScreenVideoVisibilityResult(
                titleId,
                entryId,
                videoMediaAssetId,
                null,
                "PRIVATE",
                ReconcileScreenVideoVisibilityResult.Outcome.KEPT_PRIVATE,
                "ScreenEntry or ScreenTitle is not in PUBLISHED status and no other published reference exists; video visibility preserved as PRIVATE."
        );
    }
}
