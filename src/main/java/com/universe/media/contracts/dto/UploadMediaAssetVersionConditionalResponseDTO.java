package com.universe.media.contracts.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Public response DTO for conditional media asset version upload (MS-05G9).
 *
 * <p>Exposes the asset identity, the current authoritative version number, and the
 * outcome of the conditional replacement without leaking internal persistence state.
 */
public record UploadMediaAssetVersionConditionalResponseDTO(
        UUID assetId,
        int versionNumber,
        MediaVersionUploadOutcome outcome
) {

    public UploadMediaAssetVersionConditionalResponseDTO {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        Objects.requireNonNull(outcome, "MediaVersionUploadOutcome cannot be null.");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("Version number must be greater than or equal to 1.");
        }
    }

    public static UploadMediaAssetVersionConditionalResponseDTO unchanged(UUID assetId, int currentVersionNumber) {
        return new UploadMediaAssetVersionConditionalResponseDTO(assetId, currentVersionNumber, MediaVersionUploadOutcome.UNCHANGED);
    }

    public static UploadMediaAssetVersionConditionalResponseDTO versionCreated(UUID assetId, int newVersionNumber) {
        return new UploadMediaAssetVersionConditionalResponseDTO(assetId, newVersionNumber, MediaVersionUploadOutcome.VERSION_CREATED);
    }
}
