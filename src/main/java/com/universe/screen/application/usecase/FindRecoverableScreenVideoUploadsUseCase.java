package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.FindRecoverableScreenVideoUploadsQuery;
import com.universe.screen.application.dto.RecoverableVideoUploadPageDTO;
import com.universe.screen.application.dto.RecoverableVideoUploadView;
import com.universe.screen.application.dto.RecoveryClassification;
import com.universe.screen.application.dto.ScreenMediaAssetInspectionDTO;
import com.universe.screen.application.dto.ScreenUploadSessionInfoDTO;
import com.universe.screen.application.dto.ScreenUploadSessionPageDTO;
import com.universe.screen.application.dto.ScreenVideoIntentType;
import com.universe.screen.application.dto.ScreenVideoOperationIntent;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.domain.ScreenEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case to discover completed Screen video upload sessions for recovery.
 *
 * <p><strong>Read-Only Guarantees:</strong>
 * <ul>
 *   <li>Enforces read-only database interaction with zero row-level pessimistic locks (no FOR UPDATE).</li>
 *   <li>Enforces actor isolation: strictly limits candidate discovery to sessions owned by the authenticated actor.</li>
 *   <li>Conservative classification: snapshot evaluations do not grant mutation authority and never assume in-flight safety.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class FindRecoverableScreenVideoUploadsUseCase {

    public static final String TARGET_DELETED_TITLE = "[TARGET_DELETED]";

    private final ScreenMediaValidationPort mediaValidationPort;
    private final ScreenEntryRepositoryPort entryRepository;

    public FindRecoverableScreenVideoUploadsUseCase(
            ScreenMediaValidationPort mediaValidationPort,
            ScreenEntryRepositoryPort entryRepository
    ) {
        this.mediaValidationPort = Objects.requireNonNull(mediaValidationPort, "ScreenMediaValidationPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
    }

    public RecoverableVideoUploadPageDTO execute(FindRecoverableScreenVideoUploadsQuery query) {
        Objects.requireNonNull(query, "FindRecoverableScreenVideoUploadsQuery cannot be null.");

        ScreenUploadSessionPageDTO sessionPage = mediaValidationPort.findCompletedUploadSessions(
                query.authenticatedActor(),
                query.targetEntryId(),
                query.page(),
                query.size()
        );

        List<RecoverableVideoUploadView> views = new ArrayList<>();
        for (ScreenUploadSessionInfoDTO session : sessionPage.items()) {
            views.add(mapToView(session));
        }

        return new RecoverableVideoUploadPageDTO(
                views,
                sessionPage.page(),
                sessionPage.size(),
                sessionPage.totalElements(),
                sessionPage.totalPages(),
                sessionPage.first(),
                sessionPage.last()
        );
    }

    private RecoverableVideoUploadView mapToView(ScreenUploadSessionInfoDTO session) {
        UUID sessionId = session.sessionId();
        UUID targetEntryId = session.targetEntityId();
        UUID registeredAssetId = session.registeredAssetId();

        ScreenVideoOperationIntent intent = ScreenVideoOperationIntent.parse(session.clientTag());
        String intentStr = intent.type().name();
        UUID replacementExpectedAssetId = intent.replacementExpectedAssetId();

        // Target ScreenEntry read (non-locking)
        Optional<ScreenEntry> targetEntryOpt = entryRepository.findById(targetEntryId);
        String targetEntryTitle = targetEntryOpt.map(ScreenEntry::getTitle).orElse(TARGET_DELETED_TITLE);
        UUID targetTitleId = targetEntryOpt.map(ScreenEntry::getTitleId).orElse(null);

        RecoveryClassification classification;
        boolean canRetry = false;
        boolean canDiscard = false;

        if (registeredAssetId == null) {
            classification = RecoveryClassification.INCONSISTENT;
        } else {
            Optional<ScreenMediaAssetInspectionDTO> assetOpt = mediaValidationPort.getMediaAssetInspection(registeredAssetId);
            if (assetOpt.isEmpty()) {
                classification = RecoveryClassification.INCONSISTENT;
            } else {
                ScreenMediaAssetInspectionDTO asset = assetOpt.get();
                if (!"VIDEO".equalsIgnoreCase(asset.mediaType())) {
                    classification = RecoveryClassification.INCONSISTENT;
                } else if ("DELETED".equalsIgnoreCase(asset.status())) {
                    // DELETED assets referenced by any ScreenEntry are INCONSISTENT; otherwise ALREADY_DISCARDED
                    List<UUID> referencingEntryIds = entryRepository.findEntryIdsByVideoMediaAssetId(registeredAssetId);
                    if (!referencingEntryIds.isEmpty()) {
                        classification = RecoveryClassification.INCONSISTENT;
                    } else {
                        classification = RecoveryClassification.ALREADY_DISCARDED;
                    }
                } else if (!"ACTIVE".equalsIgnoreCase(asset.status())) {
                    classification = RecoveryClassification.INCONSISTENT;
                } else {
                    // Asset is ACTIVE and type VIDEO: inspect references (non-locking)
                    List<UUID> referencingEntryIds = entryRepository.findEntryIdsByVideoMediaAssetId(registeredAssetId);
                    if (!referencingEntryIds.isEmpty()) {
                        if (referencingEntryIds.contains(targetEntryId)) {
                            classification = RecoveryClassification.ATTACHED_TO_TARGET;
                        } else {
                            classification = RecoveryClassification.REFERENCED_ELSEWHERE;
                        }
                    } else {
                        // Unreferenced: evaluate target presence
                        if (targetEntryOpt.isEmpty()) {
                            classification = RecoveryClassification.TARGET_MISSING;
                            canDiscard = true;
                        } else {
                            classification = RecoveryClassification.UNATTACHED;
                            canDiscard = true;

                            // Evaluate canRetry conservatively against target entry state
                            ScreenEntry targetEntry = targetEntryOpt.get();
                            if (intent.type() == ScreenVideoIntentType.INITIAL) {
                                canRetry = (targetEntry.getVideoMediaAssetId() == null);
                            } else if (intent.type() == ScreenVideoIntentType.REPLACEMENT) {
                                canRetry = (targetEntry.getVideoMediaAssetId() != null
                                        && targetEntry.getVideoMediaAssetId().equals(intent.replacementExpectedAssetId()));
                            } else {
                                // UNKNOWN intent fails closed for retry
                                canRetry = false;
                            }
                        }
                    }
                }
            }
        }

        return new RecoverableVideoUploadView(
                sessionId,
                registeredAssetId,
                targetEntryId,
                targetEntryTitle,
                targetTitleId,
                session.originalFilename(),
                session.declaredSizeBytes(),
                session.completedAt(),
                intentStr,
                replacementExpectedAssetId,
                classification,
                canRetry,
                canDiscard
        );
    }
}
