package com.universe.media.contracts.interfaces;

import com.universe.media.contracts.dto.ChangeMediaVisibilityRequestDTO;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;

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
