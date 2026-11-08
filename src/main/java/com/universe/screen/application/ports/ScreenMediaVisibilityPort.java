package com.universe.screen.application.ports;

import com.universe.screen.application.dto.ScreenMediaAssetInspectionDTO;

import java.util.UUID;

/**
 * Screen-owned outbound port for managing Media asset visibility lifecycle.
 *
 * <p>Enforces bounded-context separation between Screen business publication logic
 * and Media digital asset storage/delivery contracts.
 */
public interface ScreenMediaVisibilityPort {

    /**
     * Atomically locks and validates an active video media asset for update.
     *
     * <p>Acquires an exclusive row lock (FOR UPDATE) within the active ambient transaction.
     * Enforces that the asset exists, is ACTIVE, is type VIDEO, and has canonical video/mp4 MIME.
     *
     * @param videoMediaAssetId UUID of the video media asset
     */
    void lockAndValidateActiveVideoAsset(UUID videoMediaAssetId);

    /**
     * Atomically locks and inspects a video media asset for update.
     *
     * <p>Acquires an exclusive row lock (FOR UPDATE) within the active ambient transaction.
     * Returns the authoritative lifecycle status (ACTIVE, DELETED, ARCHIVED).
     *
     * @param videoMediaAssetId UUID of the video media asset
     * @return inspection result containing authoritative lifecycle status
     */
    ScreenMediaAssetInspectionDTO lockAndInspectVideoMediaAsset(UUID videoMediaAssetId);

    /**
     * Authoritatively verifies that the video media asset originates from a completed Screen upload session
     * with valid consumer context ("screen") and target entity type ("SCREEN_ENTRY").
     *
     * <p>Fails closed if the asset has foreign provenance (e.g. uploaded by novel or identity),
     * has no completed upload session, or has an invalid target binding.
     *
     * @param videoMediaAssetId UUID of the video media asset
     * @param expectedEntryId optional expected ScreenEntry UUID target binding
     */
    void validateVideoProvenance(UUID videoMediaAssetId, UUID expectedEntryId);

    /**
     * Reconciles the access visibility level of an active video media asset to PUBLIC,
     * validating target-bound provenance and authorized reuse for the given ScreenEntry target.
     *
     * @param videoMediaAssetId UUID of the video media asset
     * @param targetEntryId UUID of the target ScreenEntry receiving public playback
     */
    void makeVideoPublic(UUID videoMediaAssetId, UUID targetEntryId);

    /**
     * Reconciles the access visibility level of an active video media asset to PUBLIC.
     *
     * @param videoMediaAssetId UUID of the video media asset
     */
    default void makeVideoPublic(UUID videoMediaAssetId) {
        makeVideoPublic(videoMediaAssetId, null);
    }

    /**
     * Reconciles the access visibility level of an active video media asset to PRIVATE,
     * validating target-bound provenance if targetEntryId is provided.
     *
     * @param videoMediaAssetId UUID of the video media asset
     * @param targetEntryId optional UUID of the ScreenEntry target being unattached or archived
     */
    void makeVideoPrivate(UUID videoMediaAssetId, UUID targetEntryId);

    /**
     * Reconciles the access visibility level of an active video media asset to PRIVATE.
     *
     * @param videoMediaAssetId UUID of the video media asset
     */
    default void makeVideoPrivate(UUID videoMediaAssetId) {
        makeVideoPrivate(videoMediaAssetId, null);
    }
}
