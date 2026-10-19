package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadCommand;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult;
import com.universe.screen.application.dto.ScreenMediaAssetInspectionDTO;
import com.universe.screen.application.dto.ScreenUploadSessionInfoDTO;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenUploadSessionNotFoundException;
import com.universe.screen.application.exceptions.ScreenVideoAlreadyReferencedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardAccessDeniedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardValidationException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Core guarded discard use case for completed Screen video uploads (MS-08E2 / S2-B2).
 *
 * <p>Enforces:
 * <ul>
 *   <li>Single enclosing write transaction ({@code @Transactional}).</li>
 *   <li>Trusted server-side identity verification matching upload session initiator.</li>
 *   <li>Strict session-to-target and session-to-asset provenance checks.</li>
 *   <li>Strict lock order: MediaAsset pessimistic lock FIRST, target ScreenEntry row lock SECOND, ScreenEntry reference current-read THIRD.</li>
 *   <li>Zero unlocked ScreenEntry reads or row locks acquired before MediaAsset lock to eliminate Hibernate L1 cache staleness.</li>
 *   <li>InnoDB current-read reference guard breaking through stale REPEATABLE READ snapshots.</li>
 *   <li>Rejection without deletion if target entry disappeared or if any ScreenEntry references the asset.</li>
 *   <li>Soft-delete delegation for unreferenced ACTIVE assets.</li>
 *   <li>Authorized ALREADY_DISCARDED idempotency for unreferenced DELETED assets.</li>
 *   <li>Never deletes B2 storage objects directly; MediaUploadSession remains COMPLETED.</li>
 * </ul>
 */
@Service
@Transactional
public class DiscardScreenEntryVideoUploadUseCase {

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ScreenMediaValidationPort mediaValidationPort;

    public DiscardScreenEntryVideoUploadUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository,
            ScreenMediaValidationPort mediaValidationPort
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
        this.mediaValidationPort = Objects.requireNonNull(mediaValidationPort, "ScreenMediaValidationPort cannot be null.");
    }

    public DiscardScreenEntryVideoUploadResult execute(DiscardScreenEntryVideoUploadCommand command) {
        Objects.requireNonNull(command, "DiscardScreenEntryVideoUploadCommand cannot be null.");

        UUID titleId = Objects.requireNonNull(command.titleId(), "Title ID cannot be null.");
        UUID entryId = Objects.requireNonNull(command.entryId(), "Entry ID cannot be null.");
        UUID sessionId = Objects.requireNonNull(command.sessionId(), "Session ID cannot be null.");
        UUID videoMediaAssetId = Objects.requireNonNull(command.videoMediaAssetId(), "Video Media Asset ID cannot be null.");

        String authenticatedActor = command.authenticatedActor();
        if (authenticatedActor == null || authenticatedActor.isBlank()) {
            throw new ScreenVideoDiscardValidationException(
                    "AUTHENTICATED_ACTOR_REQUIRED",
                    "Authenticated actor cannot be null or blank."
            );
        }

        // STEP 1: Fetch upload session and verify existence (fail-closed if not found)
        ScreenUploadSessionInfoDTO session = mediaValidationPort.getUploadSession(sessionId)
                .orElseThrow(() -> new ScreenUploadSessionNotFoundException(sessionId));

        // STEP 2: OWNERSHIP CHECK FIRST: Immediately verify actor ownership before any metadata inspection or title check
        // Prevents metadata probing/disclosure of status, context, targets, or registered assets by non-owners.
        if (!authenticatedActor.trim().equals(session.initiatorPrincipalId())) {
            throw new ScreenVideoDiscardAccessDeniedException(
                    "Authenticated actor is not authorized to discard this video upload session."
            );
        }

        // STEP 3: Verify target ScreenTitle exists (only after actor ownership is proven)
        if (!titleRepository.existsById(titleId)) {
            throw new ScreenTitleNotFoundException(titleId);
        }

        // STEP 4: Verify upload session lifecycle status and immutable bindings (authorized owner only)
        if (!"COMPLETED".equalsIgnoreCase(session.status())) {
            throw new ScreenVideoDiscardValidationException(
                    "UPLOAD_SESSION_NOT_COMPLETED",
                    "Upload session [" + sessionId + "] must be COMPLETED, current status: [" + session.status() + "]."
            );
        }

        if (!"screen".equalsIgnoreCase(session.consumerContext())) {
            throw new ScreenVideoDiscardValidationException(
                    "INVALID_CONSUMER_CONTEXT",
                    "Upload session consumer context [" + session.consumerContext() + "] does not match expected [screen]."
            );
        }

        if (!"SCREEN_ENTRY".equalsIgnoreCase(session.targetEntityType())) {
            throw new ScreenVideoDiscardValidationException(
                    "INVALID_TARGET_ENTITY_TYPE",
                    "Upload session target entity type [" + session.targetEntityType() + "] does not match expected [SCREEN_ENTRY]."
            );
        }

        if (!entryId.equals(session.targetEntityId())) {
            throw new ScreenVideoDiscardValidationException(
                    "TARGET_ENTRY_ID_MISMATCH",
                    "Upload session target entry ID [" + session.targetEntityId() + "] does not match requested entry ID [" + entryId + "]."
            );
        }

        if (session.registeredAssetId() == null || !videoMediaAssetId.equals(session.registeredAssetId())) {
            throw new ScreenVideoDiscardValidationException(
                    "REGISTERED_ASSET_ID_MISMATCH",
                    "Upload session registered asset ID [" + session.registeredAssetId() + "] does not match requested video asset ID [" + videoMediaAssetId + "]."
            );
        }

        // STEP 5: FIRST LOCK IN SEQUENCE: Acquire MediaAsset exclusive pessimistic lock via S2-B1 lockAndInspectAsset
        ScreenMediaAssetInspectionDTO assetInspection = mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId);

        // Verify media type
        if (!"VIDEO".equalsIgnoreCase(assetInspection.mediaType())) {
            throw new InvalidScreenMediaAssetTypeException(videoMediaAssetId, "VIDEO", assetInspection.mediaType());
        }

        // Verify supported lifecycle status (ACTIVE or DELETED)
        String assetStatus = assetInspection.status();
        if (!"ACTIVE".equalsIgnoreCase(assetStatus) && !"DELETED".equalsIgnoreCase(assetStatus)) {
            throw new ScreenVideoDiscardValidationException(
                    "UNSUPPORTED_MEDIA_ASSET_STATUS",
                    "Media asset [" + videoMediaAssetId + "] has unsupported status [" + assetStatus + "] for discard. Expected ACTIVE or DELETED."
            );
        }

        // STEP 6: SECOND LOCK IN SEQUENCE: Revalidate target ScreenEntry under exclusive row lock (findByIdForUpdate)
        // Direct locked read ensures fresh DB state directly in JPA persistence context without stale L1 entity cache.
        ScreenEntry lockedEntry = entryRepository.findByIdForUpdate(entryId)
                .orElseThrow(() -> new ScreenEntryNotFoundException(entryId));

        if (!titleId.equals(lockedEntry.getTitleId())) {
            throw new ScreenEntryNotFoundException(entryId);
        }

        // STEP 7: THIRD LOCK IN SEQUENCE: While holding MediaAsset lock and target ScreenEntry lock, execute locking current-read
        List<UUID> referencingEntryIds = entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId);
        if (!referencingEntryIds.isEmpty()) {
            throw new ScreenVideoAlreadyReferencedException(videoMediaAssetId, referencingEntryIds);
        }

        // STEP 8: Execute discard outcome
        if ("ACTIVE".equalsIgnoreCase(assetStatus)) {
            mediaValidationPort.deleteMediaAsset(videoMediaAssetId);
            return new DiscardScreenEntryVideoUploadResult(
                    videoMediaAssetId,
                    DiscardScreenEntryVideoUploadResult.DiscardOutcome.DISCARDED,
                    "Completed video upload asset [" + videoMediaAssetId + "] successfully discarded."
            );
        } else {
            // Already DELETED and verified unreferenced
            return new DiscardScreenEntryVideoUploadResult(
                    videoMediaAssetId,
                    DiscardScreenEntryVideoUploadResult.DiscardOutcome.ALREADY_DISCARDED,
                    "Completed video upload asset [" + videoMediaAssetId + "] was already discarded."
            );
        }
    }
}
