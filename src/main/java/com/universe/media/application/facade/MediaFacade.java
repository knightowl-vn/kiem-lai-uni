package com.universe.media.application.facade;

import com.universe.media.application.asset.ArchiveMediaAssetCommand;
import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityCommand;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetCommand;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailQuery;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.MediaAssetDetailResult;
import com.universe.media.application.asset.MediaVersionItemResult;
import com.universe.media.application.asset.RestoreMediaAssetCommand;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetCommand;
import com.universe.media.application.asset.UploadMediaAssetResult;
import com.universe.media.application.asset.UploadMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionCommand;
import com.universe.media.application.asset.UploadMediaAssetVersionResult;
import com.universe.media.application.asset.UploadMediaAssetVersionUseCase;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.contracts.dto.ChangeMediaVisibilityRequestDTO;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Public facade implementing the MediaContract.
 *
 * Coordinates and delegates cross-module requests to internal Media Application use cases,
 * ensuring no internal entities, value objects, storage abstractions, or persistence details leak outward.
 */
@Service
public class MediaFacade implements MediaContract {

    private final GetMediaAssetDetailUseCase getMediaAssetDetailUseCase;
    private final ChangeMediaVisibilityUseCase changeMediaVisibilityUseCase;
    private final ArchiveMediaAssetUseCase archiveMediaAssetUseCase;
    private final RestoreMediaAssetUseCase restoreMediaAssetUseCase;
    private final DeleteMediaAssetUseCase deleteMediaAssetUseCase;
    private final UploadMediaAssetUseCase uploadMediaAssetUseCase;
    private final UploadMediaAssetVersionUseCase uploadMediaAssetVersionUseCase;

    public MediaFacade(
            GetMediaAssetDetailUseCase getMediaAssetDetailUseCase,
            ChangeMediaVisibilityUseCase changeMediaVisibilityUseCase,
            ArchiveMediaAssetUseCase archiveMediaAssetUseCase,
            RestoreMediaAssetUseCase restoreMediaAssetUseCase,
            DeleteMediaAssetUseCase deleteMediaAssetUseCase,
            UploadMediaAssetUseCase uploadMediaAssetUseCase,
            UploadMediaAssetVersionUseCase uploadMediaAssetVersionUseCase
    ) {
        this.getMediaAssetDetailUseCase = Objects.requireNonNull(
                getMediaAssetDetailUseCase,
                "GetMediaAssetDetailUseCase cannot be null."
        );
        this.changeMediaVisibilityUseCase = Objects.requireNonNull(
                changeMediaVisibilityUseCase,
                "ChangeMediaVisibilityUseCase cannot be null."
        );
        this.archiveMediaAssetUseCase = Objects.requireNonNull(
                archiveMediaAssetUseCase,
                "ArchiveMediaAssetUseCase cannot be null."
        );
        this.restoreMediaAssetUseCase = Objects.requireNonNull(
                restoreMediaAssetUseCase,
                "RestoreMediaAssetUseCase cannot be null."
        );
        this.deleteMediaAssetUseCase = Objects.requireNonNull(
                deleteMediaAssetUseCase,
                "DeleteMediaAssetUseCase cannot be null."
        );
        this.uploadMediaAssetUseCase = Objects.requireNonNull(
                uploadMediaAssetUseCase,
                "UploadMediaAssetUseCase cannot be null."
        );
        this.uploadMediaAssetVersionUseCase = Objects.requireNonNull(
                uploadMediaAssetVersionUseCase,
                "UploadMediaAssetVersionUseCase cannot be null."
        );
    }

    @Override
    public UploadMediaAssetResponseDTO uploadAsset(UploadMediaAssetRequestDTO request) {
        Objects.requireNonNull(
                request,
                "UploadMediaAssetRequestDTO cannot be null."
        );
        UploadMediaAssetCommand command = new UploadMediaAssetCommand(
                request.content(),
                request.sizeBytes(),
                request.mimeType(),
                request.mediaType(),
                request.visibility(),
                request.originalFilename()
        );
        UploadMediaAssetResult result = uploadMediaAssetUseCase.execute(command);
        return new UploadMediaAssetResponseDTO(result.assetId());
    }

    @Override
    public UploadMediaAssetVersionResponseDTO uploadVersion(UploadMediaAssetVersionRequestDTO request) {
        Objects.requireNonNull(
                request,
                "UploadMediaAssetVersionRequestDTO cannot be null."
        );
        UploadMediaAssetVersionCommand command = new UploadMediaAssetVersionCommand(
                request.assetId(),
                request.content(),
                request.sizeBytes(),
                request.mimeType(),
                request.originalFilename()
        );
        UploadMediaAssetVersionResult result = uploadMediaAssetVersionUseCase.execute(command);
        return new UploadMediaAssetVersionResponseDTO(
                result.assetId(),
                result.versionNumber()
        );
    }

    @Override
    public Optional<MediaAssetDetailDTO> getAssetDetail(UUID assetId) {
        Objects.requireNonNull(
                assetId,
                "Asset ID cannot be null."
        );

        try {
            MediaAssetDetailResult result =
                    getMediaAssetDetailUseCase.execute(new GetMediaAssetDetailQuery(assetId));
            return Optional.of(toMediaAssetDetailDTO(result));
        } catch (MediaAssetNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public void changeVisibility(ChangeMediaVisibilityRequestDTO request) {
        Objects.requireNonNull(
                request,
                "ChangeMediaVisibilityRequestDTO cannot be null."
        );
        changeMediaVisibilityUseCase.execute(
                new ChangeMediaVisibilityCommand(
                        request.assetId(),
                        request.newVisibility()
                )
        );
    }

    @Override
    public void archive(UUID assetId) {
        Objects.requireNonNull(
                assetId,
                "Asset ID cannot be null."
        );
        archiveMediaAssetUseCase.execute(
                new ArchiveMediaAssetCommand(assetId)
        );
    }

    @Override
    public void restore(UUID assetId) {
        Objects.requireNonNull(
                assetId,
                "Asset ID cannot be null."
        );
        restoreMediaAssetUseCase.execute(
                new RestoreMediaAssetCommand(assetId)
        );
    }

    @Override
    public void delete(UUID assetId) {
        Objects.requireNonNull(
                assetId,
                "Asset ID cannot be null."
        );
        deleteMediaAssetUseCase.execute(
                new DeleteMediaAssetCommand(assetId)
        );
    }

    private MediaAssetDetailDTO toMediaAssetDetailDTO(MediaAssetDetailResult result) {
        MediaVersionDTO versionDto = toMediaVersionDTO(result.currentVersion());
        return new MediaAssetDetailDTO(
                result.id(),
                result.mediaType(),
                result.visibility(),
                result.status(),
                result.currentVersionNumber(),
                result.createdAt(),
                result.updatedAt(),
                versionDto
        );
    }

    private MediaVersionDTO toMediaVersionDTO(MediaVersionItemResult versionItem) {
        return new MediaVersionDTO(
                versionItem.id(),
                versionItem.assetId(),
                versionItem.versionNumber(),
                versionItem.publicUrl(),
                versionItem.mimeType(),
                versionItem.sizeBytes(),
                versionItem.originalFilename(),
                versionItem.createdAt()
        );
    }
}
