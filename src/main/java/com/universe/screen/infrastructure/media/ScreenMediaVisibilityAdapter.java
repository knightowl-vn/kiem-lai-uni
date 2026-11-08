package com.universe.screen.infrastructure.media;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.contracts.dto.ChangeMediaVisibilityRequestDTO;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetInspectionDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaUploadSessionResponseDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.dto.ScreenMediaAssetInspectionDTO;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.exceptions.ScreenVideoProvenanceException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaVisibilityPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenFranchiseMembership;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Adapter implementing {@link ScreenMediaVisibilityPort} using the public {@link MediaContract}.
 */
@Component
public class ScreenMediaVisibilityAdapter implements ScreenMediaVisibilityPort {

    public static final String CANONICAL_VIDEO_MIME_TYPE = "video/mp4";

    private final MediaContract mediaContract;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenFranchiseMembershipRepositoryPort franchiseMembershipRepository;

    public ScreenMediaVisibilityAdapter(MediaContract mediaContract) {
        this(mediaContract, null, null, null);
    }

    public ScreenMediaVisibilityAdapter(
            MediaContract mediaContract,
            ScreenEntryRepositoryPort entryRepository,
            ScreenTitleRepositoryPort titleRepository
    ) {
        this(mediaContract, entryRepository, titleRepository, null);
    }

    @Autowired
    public ScreenMediaVisibilityAdapter(
            MediaContract mediaContract,
            @Autowired(required = false) ScreenEntryRepositoryPort entryRepository,
            @Autowired(required = false) ScreenTitleRepositoryPort titleRepository,
            @Autowired(required = false) ScreenFranchiseMembershipRepositoryPort franchiseMembershipRepository
    ) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
        this.entryRepository = entryRepository;
        this.titleRepository = titleRepository;
        this.franchiseMembershipRepository = franchiseMembershipRepository;
    }

    @Override
    public void lockAndValidateActiveVideoAsset(UUID videoMediaAssetId) {
        if (videoMediaAssetId == null) {
            return;
        }

        MediaAssetDetailDTO detail;
        try {
            detail = mediaContract.lockAndValidateActiveAsset(videoMediaAssetId, MediaTypeDTO.VIDEO);
        } catch (MediaAssetNotFoundException e) {
            throw new ScreenMediaAssetNotFoundException(videoMediaAssetId);
        }

        if (detail == null || detail.status() != MediaAssetStatusDTO.ACTIVE) {
            throw new ScreenMediaAssetNotFoundException(videoMediaAssetId);
        }

        if (detail.mediaType() != MediaTypeDTO.VIDEO) {
            throw new InvalidScreenMediaAssetTypeException(
                    videoMediaAssetId,
                    "VIDEO",
                    String.valueOf(detail.mediaType())
            );
        }

        MediaVersionDTO currentVersion = detail.currentVersion();
        if (currentVersion == null || currentVersion.mimeType() == null || currentVersion.mimeType().isBlank()) {
            throw new IllegalStateException(
                    "Video media asset [" + videoMediaAssetId + "] has no valid active version snapshot."
            );
        }

        String normalizedMime = currentVersion.mimeType().trim().toLowerCase(Locale.ROOT);
        if (!CANONICAL_VIDEO_MIME_TYPE.equals(normalizedMime)) {
            throw new IllegalStateException(
                    "Unsupported video MIME type '" + normalizedMime + "' for media asset ID "
                            + videoMediaAssetId + ". Canonical video/mp4 required."
            );
        }

        if (currentVersion.sizeBytes() <= 0) {
            throw new IllegalStateException(
                    "Video media asset [" + videoMediaAssetId + "] has invalid declared size bytes."
            );
        }
    }

    @Override
    public ScreenMediaAssetInspectionDTO lockAndInspectVideoMediaAsset(UUID videoMediaAssetId) {
        Objects.requireNonNull(videoMediaAssetId, "Video media asset ID cannot be null.");
        MediaAssetInspectionDTO inspection;
        try {
            inspection = mediaContract.lockAndInspectAsset(videoMediaAssetId, MediaTypeDTO.VIDEO);
        } catch (MediaAssetNotFoundException e) {
            throw new ScreenMediaAssetNotFoundException(videoMediaAssetId);
        }

        if (inspection == null) {
            throw new ScreenMediaAssetNotFoundException(videoMediaAssetId);
        }

        if (inspection.mediaType() != MediaTypeDTO.VIDEO) {
            throw new InvalidScreenMediaAssetTypeException(
                    videoMediaAssetId,
                    "VIDEO",
                    String.valueOf(inspection.mediaType())
            );
        }

        if (inspection.status() == null) {
            throw new IllegalStateException("Authoritative media asset inspection returned null status for " + videoMediaAssetId);
        }

        return new ScreenMediaAssetInspectionDTO(
                inspection.id(),
                inspection.mediaType() != null ? inspection.mediaType().name() : "VIDEO",
                inspection.status().name()
        );
    }

    @Override
    public void validateVideoProvenance(UUID videoMediaAssetId, UUID expectedEntryId) {
        if (videoMediaAssetId == null) {
            return;
        }

        Optional<MediaUploadSessionResponseDTO> sessionOpt = mediaContract.findCompletedSessionByAssetId(videoMediaAssetId);
        if (sessionOpt.isEmpty()) {
            throw new ScreenVideoProvenanceException(
                    "Không thể xác thực nguồn gốc video: Không tìm thấy phiên tải lên hoàn tất cho MediaAsset ID ["
                            + videoMediaAssetId + "]."
            );
        }

        MediaUploadSessionResponseDTO session = sessionOpt.get();
        if (!"screen".equalsIgnoreCase(session.consumerContext())) {
            throw new ScreenVideoProvenanceException(
                    "Vi phạm nguồn gốc video: Consumer context [" + session.consumerContext()
                            + "] không khớp với Screen context."
            );
        }

        if (!"SCREEN_ENTRY".equalsIgnoreCase(session.targetEntityType())) {
            throw new ScreenVideoProvenanceException(
                    "Vi phạm nguồn gốc video: Target entity type [" + session.targetEntityType()
                            + "] không phải SCREEN_ENTRY."
            );
        }

        if (!"COMPLETED".equalsIgnoreCase(session.status())) {
            throw new ScreenVideoProvenanceException(
                    "Vi phạm nguồn gốc video: Phiên tải lên [" + session.sessionId()
                            + "] chưa hoàn tất (status=" + session.status() + ")."
            );
        }

        UUID originalTargetEntryId = session.targetEntityId();
        if (expectedEntryId != null) {
            if (expectedEntryId.equals(originalTargetEntryId)) {
                return; // Exact target binding match
            }

            // Verify server-verifiable authorized reuse relationship
            boolean isAuthorizedReuse = isAuthorizedServerReuse(originalTargetEntryId, expectedEntryId);
            if (!isAuthorizedReuse) {
                throw new ScreenVideoProvenanceException(
                        "Vi phạm nguồn gốc video: Tái sử dụng asset không được phép. Target gốc [" + originalTargetEntryId
                                + "] và target hiện tại [" + expectedEntryId
                                + "] không có quan hệ tái sử dụng được phê duyệt (khác Title và khác Franchise)."
                );
            }
        }
    }

    private boolean isAuthorizedServerReuse(UUID originalTargetEntryId, UUID currentEntryId) {
        if (entryRepository == null || originalTargetEntryId == null || currentEntryId == null) {
            return false;
        }

        Optional<ScreenEntry> currentOpt = entryRepository.findById(currentEntryId);
        Optional<ScreenEntry> originalOpt = entryRepository.findById(originalTargetEntryId);

        if (currentOpt.isEmpty() || originalOpt.isEmpty()) {
            return false;
        }

        ScreenEntry currentEntry = currentOpt.get();
        ScreenEntry originalEntry = originalOpt.get();

        // Condition 1: Same ScreenTitle
        if (Objects.equals(currentEntry.getTitleId(), originalEntry.getTitleId())) {
            return true;
        }

        // Condition 2: Same ScreenFranchise
        if (franchiseMembershipRepository != null) {
            List<ScreenFranchiseMembership> currentMemberships = franchiseMembershipRepository.findByTitleId(currentEntry.getTitleId());
            List<ScreenFranchiseMembership> originalMemberships = franchiseMembershipRepository.findByTitleId(originalEntry.getTitleId());
            if (!currentMemberships.isEmpty() && !originalMemberships.isEmpty()) {
                Set<UUID> currentFranchiseIds = currentMemberships.stream()
                        .map(ScreenFranchiseMembership::getFranchiseId)
                        .collect(Collectors.toSet());
                boolean sharedFranchise = originalMemberships.stream()
                        .map(ScreenFranchiseMembership::getFranchiseId)
                        .anyMatch(currentFranchiseIds::contains);
                if (sharedFranchise) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public void makeVideoPublic(UUID videoMediaAssetId, UUID targetEntryId) {
        Objects.requireNonNull(videoMediaAssetId, "Video media asset ID cannot be null.");
        validateVideoProvenance(videoMediaAssetId, targetEntryId);
        mediaContract.changeVisibility(new ChangeMediaVisibilityRequestDTO(
                videoMediaAssetId,
                MediaVisibilityDTO.PUBLIC
        ));
    }

    @Override
    public void makeVideoPrivate(UUID videoMediaAssetId, UUID targetEntryId) {
        Objects.requireNonNull(videoMediaAssetId, "Video media asset ID cannot be null.");
        if (targetEntryId != null) {
            validateVideoProvenance(videoMediaAssetId, targetEntryId);
        }
        mediaContract.changeVisibility(new ChangeMediaVisibilityRequestDTO(
                videoMediaAssetId,
                MediaVisibilityDTO.PRIVATE
        ));
    }
}
