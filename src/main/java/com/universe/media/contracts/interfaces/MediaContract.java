package com.universe.media.contracts.interfaces;

import com.universe.media.contracts.dto.ChangeMediaVisibilityRequestDTO;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionResponseDTO;

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
     * Retrieves the asset summary and its current version metadata.
     *
     * @param assetId ID of the media asset
     * @return Optional containing the asset detail if present, empty otherwise
     */
    Optional<MediaAssetDetailDTO> getAssetDetail(
            UUID assetId
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
}
