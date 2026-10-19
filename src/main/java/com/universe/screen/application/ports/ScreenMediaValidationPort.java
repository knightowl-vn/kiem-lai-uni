package com.universe.screen.application.ports;

import com.universe.screen.application.dto.ScreenMediaAssetInspectionDTO;
import com.universe.screen.application.dto.ScreenUploadSessionInfoDTO;

import java.util.Optional;
import java.util.UUID;

/**
 * Screen-owned outbound port for validating Media asset references.
 *
 * <p>Preserves bounded-context boundary by relying solely on standard Java scalar types
 * without leaking Media module internal types, storage adapters, or persistence models.
 */
public interface ScreenMediaValidationPort {

    /**
     * Validates that the specified media asset exists and is a valid image asset.
     *
     * <p>If {@code mediaAssetId} is null, validation succeeds as a no-op since poster
     * and backdrop references are optional.
     *
     * @param mediaAssetId the scalar UUID of the media asset to validate
     */
    void validateImageMediaAsset(UUID mediaAssetId);

    /**
     * Validates that the specified media asset exists and is a valid video asset.
     *
     * <p>If {@code mediaAssetId} is null, validation succeeds as a no-op since video
     * attachment references are optional until attached.
     *
     * @param mediaAssetId the scalar UUID of the media asset to validate
     */
    void validateVideoMediaAsset(UUID mediaAssetId);

    /**
     * Atomically locks and validates that the specified media asset exists, is ACTIVE, and is a valid video asset.
     *
     * <p>Acquires an InnoDB exclusive row-level lock (FOR UPDATE) within the current transaction boundary.
     * The lock remains held until the calling transaction commits or rolls back.
     *
     * <p>If {@code mediaAssetId} is null, validation succeeds as a no-op.
     *
     * @param mediaAssetId the scalar UUID of the media asset to lock and validate
     */
    void lockAndValidateVideoMediaAsset(UUID mediaAssetId);

    /**
     * Retrieves upload session information for a video upload session.
     *
     * @param sessionId scalar UUID of the upload session
     * @return Optional containing session info if found, empty otherwise
     */
    Optional<ScreenUploadSessionInfoDTO> getUploadSession(UUID sessionId);

    /**
     * Atomically locks and inspects a video MediaAsset under pessimistic row lock.
     * Requires an active ambient transaction.
     *
     * @param videoMediaAssetId scalar UUID of the video media asset to inspect
     * @return inspection result containing authoritative lifecycle status
     */
    ScreenMediaAssetInspectionDTO lockAndInspectVideoMediaAsset(UUID videoMediaAssetId);

    /**
     * Soft-deletes a media asset within the active transaction boundary.
     *
     * @param mediaAssetId scalar UUID of the media asset to soft-delete
     */
    void deleteMediaAsset(UUID mediaAssetId);
}
