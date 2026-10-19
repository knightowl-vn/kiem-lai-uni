package com.universe.media.application.facade;

import com.universe.media.application.asset.ArchiveMediaAssetCommand;
import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.AssignMediaAssetClientTagCommand;
import com.universe.media.application.asset.AssignMediaAssetClientTagUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityCommand;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetCommand;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.FindActiveMediaAssetsByClientTagKeysetUseCase;
import com.universe.media.application.asset.GetCurrentMediaAssetVersionSnapshotQuery;
import com.universe.media.application.asset.GetCurrentMediaAssetVersionSnapshotUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailQuery;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.LockAndValidateActiveMediaAssetUseCase;
import com.universe.media.application.asset.GetMediaAssetCurrentMetadataQuery;
import com.universe.media.application.asset.GetMediaAssetCurrentMetadataUseCase;
import com.universe.media.application.asset.MediaAssetDetailResult;
import com.universe.media.application.asset.MediaAssetCurrentMetadataResult;
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
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalResult;
import com.universe.media.application.asset.UploadMediaAssetVersionConditionalUseCase;
import com.universe.media.application.asset.UploadMediaAssetVersionResult;
import com.universe.media.application.asset.UploadMediaAssetVersionUseCase;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.MediaAssetCandidate;
import com.universe.media.application.asset.StreamMediaAssetUseCase;
import com.universe.media.application.variant.GenerateMediaImageVariantCommand;
import com.universe.media.application.variant.GenerateMediaImageVariantUseCase;
import com.universe.media.application.storage.MediaUploadSessionService;
import com.universe.media.application.storage.MultipartManifestPart;
import com.universe.media.contracts.dto.AllocateUploadSessionRequestDTO;
import com.universe.media.contracts.dto.ChangeMediaVisibilityRequestDTO;
import com.universe.media.contracts.dto.ClaimCompletionIntentRequestDTO;
import com.universe.media.contracts.dto.CompleteMultipartUploadRequestDTO;
import com.universe.media.contracts.dto.FindActiveMediaAssetsKeysetQuery;
import com.universe.media.contracts.dto.GenerateImageVariantRequestDTO;
import com.universe.media.contracts.dto.MediaAssetCandidateDTO;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetCurrentMetadataDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaAssetStreamDTO;
import com.universe.media.contracts.dto.MediaAssetVersionContentDTO;
import com.universe.media.contracts.dto.MediaAssetVersionReferenceDTO;
import com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaUploadSessionResponseDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionConditionalResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaVisibility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final GetMediaAssetCurrentMetadataUseCase getMediaAssetCurrentMetadataUseCase;
    private final ChangeMediaVisibilityUseCase changeMediaVisibilityUseCase;
    private final ArchiveMediaAssetUseCase archiveMediaAssetUseCase;
    private final RestoreMediaAssetUseCase restoreMediaAssetUseCase;
    private final DeleteMediaAssetUseCase deleteMediaAssetUseCase;
    private final UploadMediaAssetUseCase uploadMediaAssetUseCase;
    private final UploadMediaAssetVersionUseCase uploadMediaAssetVersionUseCase;
    private final GenerateMediaImageVariantUseCase generateMediaImageVariantUseCase;
    private final GetCurrentMediaAssetVersionSnapshotUseCase getCurrentMediaAssetVersionSnapshotUseCase;
    private final OpenMediaAssetVersionContentUseCase openMediaAssetVersionContentUseCase;
    private final AssignMediaAssetClientTagUseCase assignMediaAssetClientTagUseCase;
    private final FindActiveMediaAssetsByClientTagKeysetUseCase findActiveMediaAssetsByClientTagKeysetUseCase;
    private final UploadMediaAssetVersionConditionalUseCase uploadMediaAssetVersionConditionalUseCase;
    private final MediaUploadSessionService mediaUploadSessionService;
    private final StreamMediaAssetUseCase streamMediaAssetUseCase;
    private final LockAndValidateActiveMediaAssetUseCase lockAndValidateActiveMediaAssetUseCase;

    @Autowired
    public MediaFacade(
            GetMediaAssetDetailUseCase getMediaAssetDetailUseCase,
            GetMediaAssetCurrentMetadataUseCase getMediaAssetCurrentMetadataUseCase,
            ChangeMediaVisibilityUseCase changeMediaVisibilityUseCase,
            ArchiveMediaAssetUseCase archiveMediaAssetUseCase,
            RestoreMediaAssetUseCase restoreMediaAssetUseCase,
            DeleteMediaAssetUseCase deleteMediaAssetUseCase,
            UploadMediaAssetUseCase uploadMediaAssetUseCase,
            UploadMediaAssetVersionUseCase uploadMediaAssetVersionUseCase,
            GenerateMediaImageVariantUseCase generateMediaImageVariantUseCase,
            GetCurrentMediaAssetVersionSnapshotUseCase getCurrentMediaAssetVersionSnapshotUseCase,
            OpenMediaAssetVersionContentUseCase openMediaAssetVersionContentUseCase,
            AssignMediaAssetClientTagUseCase assignMediaAssetClientTagUseCase,
            FindActiveMediaAssetsByClientTagKeysetUseCase findActiveMediaAssetsByClientTagKeysetUseCase,
            UploadMediaAssetVersionConditionalUseCase uploadMediaAssetVersionConditionalUseCase,
            LockAndValidateActiveMediaAssetUseCase lockAndValidateActiveMediaAssetUseCase,
            @Autowired(required = false) MediaUploadSessionService mediaUploadSessionService,
            @Autowired(required = false) StreamMediaAssetUseCase streamMediaAssetUseCase
    ) {
        this.getMediaAssetDetailUseCase = Objects.requireNonNull(
                getMediaAssetDetailUseCase,
                "GetMediaAssetDetailUseCase cannot be null."
        );
        this.getMediaAssetCurrentMetadataUseCase = Objects.requireNonNull(
                getMediaAssetCurrentMetadataUseCase,
                "GetMediaAssetCurrentMetadataUseCase cannot be null."
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
        this.assignMediaAssetClientTagUseCase = Objects.requireNonNull(
                assignMediaAssetClientTagUseCase,
                "AssignMediaAssetClientTagUseCase cannot be null."
        );
        this.findActiveMediaAssetsByClientTagKeysetUseCase = Objects.requireNonNull(
                findActiveMediaAssetsByClientTagKeysetUseCase,
                "FindActiveMediaAssetsByClientTagKeysetUseCase cannot be null."
        );
        this.uploadMediaAssetVersionConditionalUseCase = Objects.requireNonNull(
                uploadMediaAssetVersionConditionalUseCase,
                "UploadMediaAssetVersionConditionalUseCase cannot be null."
        );
        this.mediaUploadSessionService = mediaUploadSessionService;
        this.streamMediaAssetUseCase = streamMediaAssetUseCase;
        this.lockAndValidateActiveMediaAssetUseCase = lockAndValidateActiveMediaAssetUseCase;
    }

    public MediaFacade(
            GetMediaAssetDetailUseCase getMediaAssetDetailUseCase,
            GetMediaAssetCurrentMetadataUseCase getMediaAssetCurrentMetadataUseCase,
            ChangeMediaVisibilityUseCase changeMediaVisibilityUseCase,
            ArchiveMediaAssetUseCase archiveMediaAssetUseCase,
            RestoreMediaAssetUseCase restoreMediaAssetUseCase,
            DeleteMediaAssetUseCase deleteMediaAssetUseCase,
            UploadMediaAssetUseCase uploadMediaAssetUseCase,
            UploadMediaAssetVersionUseCase uploadMediaAssetVersionUseCase,
            GenerateMediaImageVariantUseCase generateMediaImageVariantUseCase,
            GetCurrentMediaAssetVersionSnapshotUseCase getCurrentMediaAssetVersionSnapshotUseCase,
            OpenMediaAssetVersionContentUseCase openMediaAssetVersionContentUseCase,
            AssignMediaAssetClientTagUseCase assignMediaAssetClientTagUseCase,
            FindActiveMediaAssetsByClientTagKeysetUseCase findActiveMediaAssetsByClientTagKeysetUseCase,
            UploadMediaAssetVersionConditionalUseCase uploadMediaAssetVersionConditionalUseCase
    ) {
        this(
                getMediaAssetDetailUseCase,
                getMediaAssetCurrentMetadataUseCase,
                changeMediaVisibilityUseCase,
                archiveMediaAssetUseCase,
                restoreMediaAssetUseCase,
                deleteMediaAssetUseCase,
                uploadMediaAssetUseCase,
                uploadMediaAssetVersionUseCase,
                generateMediaImageVariantUseCase,
                getCurrentMediaAssetVersionSnapshotUseCase,
                openMediaAssetVersionContentUseCase,
                assignMediaAssetClientTagUseCase,
                findActiveMediaAssetsByClientTagKeysetUseCase,
                uploadMediaAssetVersionConditionalUseCase,
                null,
                null,
                null
        );
    }

    public MediaFacade(
            GetMediaAssetDetailUseCase getMediaAssetDetailUseCase,
            GetMediaAssetCurrentMetadataUseCase getMediaAssetCurrentMetadataUseCase,
            ChangeMediaVisibilityUseCase changeMediaVisibilityUseCase,
            ArchiveMediaAssetUseCase archiveMediaAssetUseCase,
            RestoreMediaAssetUseCase restoreMediaAssetUseCase,
            DeleteMediaAssetUseCase deleteMediaAssetUseCase,
            UploadMediaAssetUseCase uploadMediaAssetUseCase,
            UploadMediaAssetVersionUseCase uploadMediaAssetVersionUseCase,
            GenerateMediaImageVariantUseCase generateMediaImageVariantUseCase,
            GetCurrentMediaAssetVersionSnapshotUseCase getCurrentMediaAssetVersionSnapshotUseCase,
            OpenMediaAssetVersionContentUseCase openMediaAssetVersionContentUseCase,
            AssignMediaAssetClientTagUseCase assignMediaAssetClientTagUseCase,
            FindActiveMediaAssetsByClientTagKeysetUseCase findActiveMediaAssetsByClientTagKeysetUseCase,
            UploadMediaAssetVersionConditionalUseCase uploadMediaAssetVersionConditionalUseCase,
            LockAndValidateActiveMediaAssetUseCase lockAndValidateActiveMediaAssetUseCase
    ) {
        this(
                getMediaAssetDetailUseCase,
                getMediaAssetCurrentMetadataUseCase,
                changeMediaVisibilityUseCase,
                archiveMediaAssetUseCase,
                restoreMediaAssetUseCase,
                deleteMediaAssetUseCase,
                uploadMediaAssetUseCase,
                uploadMediaAssetVersionUseCase,
                generateMediaImageVariantUseCase,
                getCurrentMediaAssetVersionSnapshotUseCase,
                openMediaAssetVersionContentUseCase,
                assignMediaAssetClientTagUseCase,
                findActiveMediaAssetsByClientTagKeysetUseCase,
                uploadMediaAssetVersionConditionalUseCase,
                lockAndValidateActiveMediaAssetUseCase,
                null,
                null
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
                request.originalFilename(),
                request.clientTag()
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
    public UploadMediaAssetVersionConditionalResponseDTO uploadVersionIfContentChanged(UploadMediaAssetVersionRequestDTO request) {
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
        UploadMediaAssetVersionConditionalResult result = uploadMediaAssetVersionConditionalUseCase.execute(command);
        return new UploadMediaAssetVersionConditionalResponseDTO(
                result.assetId(),
                result.versionNumber(),
                result.outcome()
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
    public Optional<MediaAssetCurrentMetadataDTO> getAssetCurrentMetadata(UUID assetId) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");

        try {
            MediaAssetCurrentMetadataResult result = getMediaAssetCurrentMetadataUseCase.execute(
                    new GetMediaAssetCurrentMetadataQuery(assetId)
            );
            return Optional.of(new MediaAssetCurrentMetadataDTO(
                    result.id(),
                    toMediaAssetStatusDTO(result.status()),
                    toMediaVisibilityDTO(result.visibility()),
                    result.currentVersionNumber()
            ));
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

    @Override
    public void assignClientTagIfAbsent(UUID assetId, String clientTag) {
        Objects.requireNonNull(
                assetId,
                "Asset ID cannot be null."
        );
        Objects.requireNonNull(
                clientTag,
                "Client tag cannot be null."
        );
        assignMediaAssetClientTagUseCase.execute(
                new AssignMediaAssetClientTagCommand(assetId, clientTag)
        );
    }

    @Override
    public List<MediaAssetCandidateDTO> findActiveAssetsByClientTagKeyset(
            FindActiveMediaAssetsKeysetQuery query
    ) {
        Objects.requireNonNull(
                query,
                "FindActiveMediaAssetsKeysetQuery cannot be null."
        );
        com.universe.media.application.asset.FindActiveMediaAssetsKeysetQuery applicationQuery =
                new com.universe.media.application.asset.FindActiveMediaAssetsKeysetQuery(
                        query.clientTag(),
                        query.createdBeforeUpperBound(),
                        query.lastCreatedAt(),
                        query.lastAssetId(),
                        query.pageSize()
                );
        List<MediaAssetCandidate> candidates =
                findActiveMediaAssetsByClientTagKeysetUseCase.execute(applicationQuery);
        return candidates.stream()
                .map(c -> new MediaAssetCandidateDTO(c.assetId(), c.createdAt()))
                .toList();
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

    @Override
    public MediaUploadSessionResponseDTO allocateUploadSession(AllocateUploadSessionRequestDTO request) {
        Objects.requireNonNull(request, "AllocateUploadSessionRequestDTO cannot be null.");
        ensureUploadSessionServiceAvailable();
        MediaUploadSession session = mediaUploadSessionService.allocateSession(
                request.initiatorPrincipalId(),
                request.consumerContext(),
                request.targetEntityType(),
                request.targetEntityId(),
                request.idempotencyKey(),
                request.declaredSizeBytes(),
                request.originalFilename(),
                request.clientTag()
        );
        return toUploadSessionResponseDTO(session);
    }

    @Override
    public MediaUploadSessionResponseDTO initiateUploadSession(UUID sessionId, String requestingPrincipalId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        Objects.requireNonNull(requestingPrincipalId, "requestingPrincipalId cannot be null.");
        ensureUploadSessionServiceAvailable();
        MediaUploadSession session = mediaUploadSessionService.initiateMultipartUpload(sessionId, requestingPrincipalId);
        return toUploadSessionResponseDTO(session);
    }

    @Override
    public String generatePresignedPartUploadUrl(UUID sessionId, int partNumber, String requestingPrincipalId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        Objects.requireNonNull(requestingPrincipalId, "requestingPrincipalId cannot be null.");
        ensureUploadSessionServiceAvailable();
        return mediaUploadSessionService.generatePresignedPartUploadUrl(sessionId, partNumber, requestingPrincipalId);
    }

    @Override
    public MediaUploadSessionResponseDTO claimCompletionIntent(ClaimCompletionIntentRequestDTO request) {
        Objects.requireNonNull(request, "ClaimCompletionIntentRequestDTO cannot be null.");
        ensureUploadSessionServiceAvailable();
        List<MultipartManifestPart> domainManifest = request.manifest().stream()
                .map(p -> new MultipartManifestPart(p.partNumber(), p.sizeBytes(), p.eTag()))
                .toList();
        MediaUploadSession session = mediaUploadSessionService.claimCompletionIntent(
                request.sessionId(),
                request.requestingPrincipalId(),
                request.targetEntityType(),
                request.targetEntityId(),
                request.declaredContentHash(),
                domainManifest
        );
        return toUploadSessionResponseDTO(session);
    }

    @Override
    public MediaUploadSessionResponseDTO completeMultipartUpload(CompleteMultipartUploadRequestDTO request) {
        Objects.requireNonNull(request, "CompleteMultipartUploadRequestDTO cannot be null.");
        ensureUploadSessionServiceAvailable();
        List<MultipartManifestPart> domainManifest = (request.manifest() != null)
                ? request.manifest().stream().map(p -> new MultipartManifestPart(p.partNumber(), p.sizeBytes(), p.eTag())).toList()
                : null;
        MediaUploadSession session = mediaUploadSessionService.completeMultipartUpload(
                request.sessionId(),
                request.requestingPrincipalId(),
                domainManifest
        );
        return toUploadSessionResponseDTO(session);
    }

    @Override
    public MediaUploadSessionResponseDTO abortUploadSession(UUID sessionId, String requestingPrincipalId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        Objects.requireNonNull(requestingPrincipalId, "requestingPrincipalId cannot be null.");
        ensureUploadSessionServiceAvailable();
        MediaUploadSession session = mediaUploadSessionService.abortSession(sessionId, requestingPrincipalId);
        return toUploadSessionResponseDTO(session);
    }

    @Override
    public Optional<MediaUploadSessionResponseDTO> getUploadSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        ensureUploadSessionServiceAvailable();
        return mediaUploadSessionService.findById(sessionId)
                .map(this::toUploadSessionResponseDTO);
    }

    private MediaUploadSessionResponseDTO toUploadSessionResponseDTO(MediaUploadSession session) {
        if (session == null) {
            return null;
        }
        return new MediaUploadSessionResponseDTO(
                session.getId(),
                session.getStatus().name(),
                session.getInitiatorPrincipalId(),
                session.getConsumerContext(),
                session.getTargetEntityType(),
                session.getTargetEntityId(),
                session.getDeclaredSizeBytes(),
                session.getPartSizeBytes(),
                session.getExpectedPartCount(),
                session.getOriginalFilename(),
                session.getClientTag(),
                session.getRegisteredAssetId(),
                session.getCreatedAt(),
                session.getExpiresAt()
        );
    }

    private void ensureUploadSessionServiceAvailable() {
        if (mediaUploadSessionService == null) {
            throw new IllegalStateException("MediaUploadSessionService is not configured or available.");
        }
    }

    @Override
    public MediaAssetStreamDTO openStream(UUID assetId) {
        ensureStreamMediaAssetUseCaseAvailable();
        return streamMediaAssetUseCase.openStream(assetId);
    }

    @Override
    public MediaAssetStreamDTO openRangeStream(UUID assetId, long startInclusive, long length) {
        ensureStreamMediaAssetUseCaseAvailable();
        return streamMediaAssetUseCase.openRangeStream(assetId, startInclusive, length);
    }

    private void ensureStreamMediaAssetUseCaseAvailable() {
        if (streamMediaAssetUseCase == null) {
            throw new IllegalStateException("StreamMediaAssetUseCase is not configured or available.");
        }
    }

    @Override
    public MediaAssetDetailDTO lockAndValidateActiveAsset(UUID assetId, MediaTypeDTO expectedType) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        ensureLockAndValidateUseCaseAvailable();
        MediaType domainExpectedType = toDomainMediaType(expectedType);
        MediaAssetDetailResult result = lockAndValidateActiveMediaAssetUseCase.execute(assetId, domainExpectedType);
        return toMediaAssetDetailDTO(result);
    }

    private void ensureLockAndValidateUseCaseAvailable() {
        if (lockAndValidateActiveMediaAssetUseCase == null) {
            throw new IllegalStateException("LockAndValidateActiveMediaAssetUseCase is not configured or available.");
        }
    }
}
