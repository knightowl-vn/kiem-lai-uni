package com.universe.screen.infrastructure.media;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetInspectionDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaUploadSessionPageDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.dto.ScreenMediaAssetInspectionDTO;
import com.universe.screen.application.dto.ScreenUploadSessionInfoDTO;
import com.universe.screen.application.dto.ScreenUploadSessionPageDTO;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ScreenMediaValidationAdapter implements ScreenMediaValidationPort {

    private final MediaContract mediaContract;

    public ScreenMediaValidationAdapter(MediaContract mediaContract) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
    }

    @Override
    public void validateImageMediaAsset(UUID mediaAssetId) {
        if (mediaAssetId == null) {
            return;
        }

        Optional<MediaAssetDetailDTO> detailOpt = mediaContract.getAssetDetail(mediaAssetId);
        if (detailOpt.isEmpty()) {
            throw new ScreenMediaAssetNotFoundException(mediaAssetId);
        }

        MediaAssetDetailDTO detail = detailOpt.get();
        if (detail.status() == MediaAssetStatusDTO.DELETED) {
            throw new ScreenMediaAssetNotFoundException(mediaAssetId);
        }

        if (detail.mediaType() != MediaTypeDTO.IMAGE) {
            throw new InvalidScreenMediaAssetTypeException(mediaAssetId, String.valueOf(detail.mediaType()));
        }
    }

    @Override
    public void validateVideoMediaAsset(UUID mediaAssetId) {
        if (mediaAssetId == null) {
            return;
        }

        Optional<MediaAssetDetailDTO> detailOpt = mediaContract.getAssetDetail(mediaAssetId);
        if (detailOpt.isEmpty()) {
            throw new ScreenMediaAssetNotFoundException(mediaAssetId);
        }

        MediaAssetDetailDTO detail = detailOpt.get();
        if (detail.status() == MediaAssetStatusDTO.DELETED) {
            throw new ScreenMediaAssetNotFoundException(mediaAssetId);
        }

        if (detail.mediaType() != MediaTypeDTO.VIDEO) {
            throw new InvalidScreenMediaAssetTypeException(mediaAssetId, "VIDEO", String.valueOf(detail.mediaType()));
        }
    }

    @Override
    public void lockAndValidateVideoMediaAsset(UUID mediaAssetId) {
        if (mediaAssetId == null) {
            return;
        }

        MediaAssetDetailDTO detail;
        try {
            detail = mediaContract.lockAndValidateActiveAsset(mediaAssetId, null);
        } catch (MediaAssetNotFoundException e) {
            throw new ScreenMediaAssetNotFoundException(mediaAssetId);
        }

        if (detail == null || detail.status() == MediaAssetStatusDTO.DELETED) {
            throw new ScreenMediaAssetNotFoundException(mediaAssetId);
        }

        if (detail.mediaType() != MediaTypeDTO.VIDEO) {
            throw new InvalidScreenMediaAssetTypeException(mediaAssetId, "VIDEO", String.valueOf(detail.mediaType()));
        }
    }

    @Override
    public Optional<ScreenUploadSessionInfoDTO> getUploadSession(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return mediaContract.getUploadSession(sessionId)
                .map(s -> new ScreenUploadSessionInfoDTO(
                        s.sessionId(),
                        s.status(),
                        s.initiatorPrincipalId(),
                        s.consumerContext(),
                        s.targetEntityType(),
                        s.targetEntityId(),
                        s.registeredAssetId(),
                        s.originalFilename(),
                        s.declaredSizeBytes(),
                        s.clientTag(),
                        s.createdAt(),
                        s.completedAt()
                ));
    }

    @Override
    public ScreenMediaAssetInspectionDTO lockAndInspectVideoMediaAsset(UUID videoMediaAssetId) {
        Objects.requireNonNull(videoMediaAssetId, "Video media asset ID cannot be null.");
        MediaAssetInspectionDTO inspection;
        try {
            inspection = mediaContract.lockAndInspectAsset(videoMediaAssetId, null);
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

        return new ScreenMediaAssetInspectionDTO(
                inspection.id(),
                String.valueOf(inspection.mediaType()),
                String.valueOf(inspection.status())
        );
    }

    @Override
    public void deleteMediaAsset(UUID mediaAssetId) {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");
        mediaContract.delete(mediaAssetId);
    }

    @Override
    public ScreenUploadSessionPageDTO findCompletedUploadSessions(
            String initiatorPrincipalId,
            UUID targetEntityId,
            int page,
            int size
    ) {
        if (initiatorPrincipalId == null || initiatorPrincipalId.isBlank()) {
            return ScreenUploadSessionPageDTO.empty(page, size);
        }

        MediaUploadSessionPageDTO pageResult = mediaContract.findCompletedUploadSessions(
                initiatorPrincipalId.trim(),
                "screen",
                "SCREEN_ENTRY",
                "VIDEO",
                targetEntityId,
                page,
                size
        );

        List<ScreenUploadSessionInfoDTO> items = pageResult.items().stream()
                .map(s -> new ScreenUploadSessionInfoDTO(
                        s.sessionId(),
                        s.status(),
                        s.initiatorPrincipalId(),
                        s.consumerContext(),
                        s.targetEntityType(),
                        s.targetEntityId(),
                        s.registeredAssetId(),
                        s.originalFilename(),
                        s.declaredSizeBytes(),
                        s.clientTag(),
                        s.createdAt(),
                        s.completedAt()
                ))
                .toList();

        return new ScreenUploadSessionPageDTO(
                items,
                pageResult.page(),
                pageResult.size(),
                pageResult.totalElements(),
                pageResult.totalPages(),
                pageResult.first(),
                pageResult.last()
        );
    }

    @Override
    public Optional<ScreenMediaAssetInspectionDTO> getMediaAssetInspection(UUID mediaAssetId) {
        if (mediaAssetId == null) {
            return Optional.empty();
        }
        return mediaContract.getAssetDetail(mediaAssetId)
                .map(detail -> new ScreenMediaAssetInspectionDTO(
                        detail.id(),
                        String.valueOf(detail.mediaType()),
                        String.valueOf(detail.status())
                ));
    }
}
