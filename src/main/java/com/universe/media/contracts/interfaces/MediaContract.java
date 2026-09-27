package com.universe.media.contracts.interfaces;

import com.universe.media.contracts.dto.ChangeMediaVisibilityRequestDTO;
import com.universe.media.contracts.dto.FindActiveMediaAssetsKeysetQuery;
import com.universe.media.contracts.dto.GenerateImageVariantRequestDTO;
import com.universe.media.contracts.dto.MediaAssetCandidateDTO;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetCurrentMetadataDTO;
import com.universe.media.contracts.dto.MediaAssetVersionContentDTO;
import com.universe.media.contracts.dto.MediaAssetVersionReferenceDTO;
import com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionConditionalResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionResponseDTO;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public Contract interface of the Media Module.
 *
 * Exposes core digital asset operations to consumer modules (e.g. Novel, Wiki, Identity, Narration)
 * without leaking Media Application internals, persistence, or vendor storage abstractions.
 */
public interface MediaContract {

    /**
     * Uploads a new media asset binary and registers its initial metadata.
     *
     * <p><strong>Stream Ownership:</strong> The caller retains ownership of the request
     * {@link java.io.InputStream}. The Media module reads from the stream to store and hash the binary
     * but does not close it. The caller is responsible for closing the stream after execution.
     *
     * @param request upload request
     * @return upload response containing the new asset ID
     */
    UploadMediaAssetResponseDTO uploadAsset(
            UploadMediaAssetRequestDTO request
    );

    /**
     * Uploads a new binary version for an existing media asset.
     *
     * <p><strong>Stream Ownership:</strong> The caller retains ownership of the request
     * {@link java.io.InputStream}. The Media module reads from the stream to store and hash the binary
     * but does not close it. The caller is responsible for closing the stream after execution.
     *
     * @param request upload version request
     * @return upload version response containing the asset ID and newly registered version number
     */
    UploadMediaAssetVersionResponseDTO uploadVersion(
            UploadMediaAssetVersionRequestDTO request
    );

    /**
     * Conditionally uploads a new binary version for an existing media asset only if the uploaded
     * binary's SHA-256 digest differs from the current authoritative version's content hash (MS-05G9).
     *
     * <p>If the uploaded binary is identical to the current authoritative version, no new version
     * is created, no storage write occurs, and {@link com.universe.media.contracts.dto.MediaVersionUploadOutcome#UNCHANGED}
     * is returned. If the binary differs, a new immutable version is stored and persisted, returning
     * {@link com.universe.media.contracts.dto.MediaVersionUploadOutcome#VERSION_CREATED}.
     *
     * <p><strong>Stream Ownership:</strong> The caller retains ownership of the request
     * {@link java.io.InputStream}. The caller is responsible for closing the stream after execution.
     *
     * @param request upload version request
     * @return conditional response containing the asset ID, version number, and outcome
     */
    UploadMediaAssetVersionConditionalResponseDTO uploadVersionIfContentChanged(
            UploadMediaAssetVersionRequestDTO request
    );

    /**
     * Synchronously generates an image variant for the current version of an existing image asset.
     *
     * @param request variant generation request
     */
    void generateImageVariant(
            GenerateImageVariantRequestDTO request
    );

    /**
     * Retrieves the asset summary and its current version metadata.
     *
     * @param assetId ID of the media asset
     * @return Optional containing the asset detail if present, empty otherwise
     */
    Optional<MediaAssetDetailDTO> getAssetDetail(
            UUID assetId
    );

    /**
     * Retrieves lightweight asset metadata after resolving its declared current version.
     *
     * @param assetId ID of the media asset
     * @return Optional containing current metadata if the asset is present, empty otherwise
     */
    Optional<MediaAssetCurrentMetadataDTO> getAssetCurrentMetadata(
            UUID assetId
    );

    /**
     * Retrieves immutable provenance for the current binary version of a non-deleted media asset.
     *
     * @param assetId ID of the media asset
     * @return Optional containing the current version snapshot if present, empty otherwise
     */
    Optional<MediaAssetVersionSnapshotDTO> getCurrentVersionSnapshot(
            UUID assetId
    );

    /**
     * Opens the exact immutable binary version identified by asset ID, version number, and content hash.
     *
     * <p><strong>Stream Ownership:</strong> The caller owns and must close the returned
     * {@link java.io.InputStream}.
     *
     * @param reference exact immutable version reference
     * @return content stream and metadata for the referenced version
     */
    MediaAssetVersionContentDTO openVersionContent(
            MediaAssetVersionReferenceDTO reference
    );

    /**
     * Mutates the access visibility level of an asset (PUBLIC, PRIVATE, RESTRICTED).
     *
     * @param request change visibility request
     */
    void changeVisibility(
            ChangeMediaVisibilityRequestDTO request
    );

    /**
     * Transitions an active asset to ARCHIVED state.
     *
     * @param assetId ID of the media asset
     */
    void archive(
            UUID assetId
    );

    /**
     * Restores an archived asset back to ACTIVE state.
     *
     * @param assetId ID of the media asset
     */
    void restore(
            UUID assetId
    );

    /**
     * Transitions an asset to terminal DELETED state.
     *
     * @param assetId ID of the media asset
     */
    void delete(
            UUID assetId
    );

    /**
     * Assigns an opaque client tag to an existing media asset if currently absent (null).
     *
     * <p>If the asset already has the identical tag, the operation is an idempotent noop.
     * If the asset already has a different non-null tag, a {@link com.universe.media.domain.ClientTagConflictException} is thrown.
     *
     * @param assetId ID of the media asset
     * @param clientTag the opaque client tag to assign
     */
    void assignClientTagIfAbsent(
            UUID assetId,
            String clientTag
    );

    /**
     * Discovers active media assets matching an opaque client tag using starvation-safe keyset pagination.
     *
     * @param query keyset query parameters
     * @return bounded list of active asset candidates ordered by (created_at ASC, id ASC)
     */
    List<MediaAssetCandidateDTO> findActiveAssetsByClientTagKeyset(
            FindActiveMediaAssetsKeysetQuery query
    );
}
