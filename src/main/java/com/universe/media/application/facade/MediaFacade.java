package com.universe.media.application.facade;

import com.universe.media.application.asset.ArchiveMediaAssetCommand;
import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityCommand;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetCommand;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.GetCurrentMediaAssetVersionSnapshotQuery;
import com.universe.media.application.asset.GetCurrentMediaAssetVersionSnapshotUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailQuery;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.MediaAssetDetailResult;
import com.universe.media.application.asset.MediaAssetVersionContentResult;
import com.universe.media.application.asset.MediaAssetVersionSnapshotResult;
import com.universe.media.application.asset.MediaVersionItemResult;
import com.universe.media.application.asset.OpenMediaAssetVersionContentQuery;
import com.universe.media.application.asset.OpenMediaAssetVersionContentUseCase;
import com.universe.media.application.asset.RestoreMediaAssetCommand;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetCommand;
import com.universe.media.application.asset.UploadMediaAssetResult;
import com.universe.media.application.asset.UploadMediaAssetUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionCommand;
import com.universe.media.application.asset.UploadMediaAssetVersionResult;
import com.universe.media.application.asset.UploadMediaAssetVersionUseCase;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.variant.GenerateMediaImageVariantCommand;
import com.universe.media.application.variant.GenerateMediaImageVariantUseCase;
import com.universe.media.contracts.dto.ChangeMediaVisibilityRequestDTO;
import com.universe.media.contracts.dto.GenerateImageVariantRequestDTO;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaAssetVersionContentDTO;
import com.universe.media.contracts.dto.MediaAssetVersionReferenceDTO;
import com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
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
    private final GenerateMediaImageVariantUseCase generateMediaImageVariantUseCase;
    private final GetCurrentMediaAssetVersionSnapshotUseCase getCurrentMediaAssetVersionSnapshotUseCase;
    private final OpenMediaAssetVersionContentUseCase openMediaAssetVersionContentUseCase;

    public MediaFacade(
            GetMediaAssetDetailUseCase getMediaAssetDetailUseCase,
            ChangeMediaVisibilityUseCase changeMediaVisibilityUseCase,
            ArchiveMediaAssetUseCase archiveMediaAssetUseCase,
            RestoreMediaAssetUseCase restoreMediaAssetUseCase,
            DeleteMediaAssetUseCase deleteMediaAssetUseCase,
            UploadMediaAssetUseCase uploadMediaAssetUseCase,
            UploadMediaAssetVersionUseCase uploadMediaAssetVersionUseCase,
            GenerateMediaImageVariantUseCase generateMediaImageVariantUseCase,
            GetCurrentMediaAssetVersionSnapshotUseCase getCurrentMediaAssetVersionSnapshotUseCase,
            OpenMediaAssetVersionContentUseCase openMediaAssetVersionContentUseCase
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
        this.generateMediaImageVariantUseCase = Objects.requireNonNull(
                generateMediaImageVariantUseCase,
                "GenerateMediaImageVariantUseCase cannot be null."
        );
        this.getCurrentMediaAssetVersionSnapshotUseCase = Objects.requireNonNull(
                getCurrentMediaAssetVersionSnapshotUseCase,
                "GetCurrentMediaAssetVersionSnapshotUseCase cannot be null."
        );
        this.openMediaAssetVersionContentUseCase = Objects.requireNonNull(
                openMediaAssetVersionContentUseCase,
                "OpenMediaAssetVersionContentUseCase cannot be null."
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
                toDomainMediaType(request.mediaType()),
                toDomainVisibility(request.visibility()),
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
    public void generateImageVariant(GenerateImageVariantRequestDTO request) {
        Objects.requireNonNull(
                request,
                "GenerateImageVariantRequestDTO cannot be null."
        );
        Objects.requireNonNull(
                request.mediaAssetId(),
                "Media asset ID cannot be null."
        );
        GenerateMediaImageVariantCommand command = GenerateMediaImageVariantCommand.of(
                request.mediaAssetId(),
                ImageVariantSpec.of(request.targetWidth())
        );
        generateMediaImageVariantUseCase.execute(command);
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
    public Optional<MediaAssetVersionSnapshotDTO> getCurrentVersionSnapshot(UUID assetId) {
        Objects.requireNonNull(
                assetId,
                "Asset ID cannot be null."
        );

        try {
            MediaAssetVersionSnapshotResult result = getCurrentMediaAssetVersionSnapshotUseCase.execute(
                    new GetCurrentMediaAssetVersionSnapshotQuery(assetId)
            );
            return Optional.of(toMediaAssetVersionSnapshotDTO(result));
        } catch (MediaAssetNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public MediaAssetVersionContentDTO openVersionContent(MediaAssetVersionReferenceDTO reference) {
        Objects.requireNonNull(
                reference,
                "MediaAssetVersionReferenceDTO cannot be null."
        );

        MediaAssetVersionContentResult result = openMediaAssetVersionContentUseCase.execute(
                new OpenMediaAssetVersionContentQuery(
                        reference.assetId(),
                        reference.versionNumber(),
                        reference.contentHash()
                )
        );
        return new MediaAssetVersionContentDTO(
                result.assetId(),
                result.versionNumber(),
                result.contentHash(),
                result.mimeType(),
                result.sizeBytes(),
                result.content()
        );
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
                        toDomainVisibility(request.newVisibility())
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
                toMediaTypeDTO(result.mediaType()),
                toMediaVisibilityDTO(result.visibility()),
                toMediaAssetStatusDTO(result.status()),
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

    private MediaAssetVersionSnapshotDTO toMediaAssetVersionSnapshotDTO(
            MediaAssetVersionSnapshotResult result
    ) {
        return new MediaAssetVersionSnapshotDTO(
                result.assetId(),
                result.versionNumber(),
                result.contentHash(),
                result.mimeType(),
                result.sizeBytes(),
                result.originalFilename()
        );
    }

    private MediaType toDomainMediaType(MediaTypeDTO dto) {
        if (dto == null) {
            return null;
        }
        return switch (dto) {
            case IMAGE -> MediaType.IMAGE;
            case AUDIO -> MediaType.AUDIO;
            case VIDEO -> MediaType.VIDEO;
            case DOCUMENT -> MediaType.DOCUMENT;
        };
    }

    private MediaVisibility toDomainVisibility(MediaVisibilityDTO dto) {
        if (dto == null) {
            return null;
        }
        return switch (dto) {
            case PUBLIC -> MediaVisibility.PUBLIC;
            case PRIVATE -> MediaVisibility.PRIVATE;
            case RESTRICTED -> MediaVisibility.RESTRICTED;
        };
    }

    private MediaTypeDTO toMediaTypeDTO(MediaType domain) {
        if (domain == null) {
            return null;
        }
        return switch (domain) {
            case IMAGE -> MediaTypeDTO.IMAGE;
            case AUDIO -> MediaTypeDTO.AUDIO;
            case VIDEO -> MediaTypeDTO.VIDEO;
            case DOCUMENT -> MediaTypeDTO.DOCUMENT;
        };
    }

    private MediaVisibilityDTO toMediaVisibilityDTO(MediaVisibility domain) {
        if (domain == null) {
            return null;
        }
        return switch (domain) {
            case PUBLIC -> MediaVisibilityDTO.PUBLIC;
            case PRIVATE -> MediaVisibilityDTO.PRIVATE;
            case RESTRICTED -> MediaVisibilityDTO.RESTRICTED;
        };
    }

    private MediaAssetStatusDTO toMediaAssetStatusDTO(MediaAssetStatus domain) {
        if (domain == null) {
            return null;
        }
        return switch (domain) {
            case ACTIVE -> MediaAssetStatusDTO.ACTIVE;
            case ARCHIVED -> MediaAssetStatusDTO.ARCHIVED;
            case DELETED -> MediaAssetStatusDTO.DELETED;
        };
    }
}
