package com.universe.media.application.asset;

import com.universe.media.contracts.dto.MediaVersionUploadOutcome;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of conditional media asset version registration (MS-05G9).
 */
public record RegisterMediaAssetVersionConditionalResult(
        UUID assetId,
        UUID versionId,
        int versionNumber,
        MediaVersionUploadOutcome outcome,
        Instant updatedAt
) {

    public RegisterMediaAssetVersionConditionalResult {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        Objects.requireNonNull(outcome, "MediaVersionUploadOutcome cannot be null.");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("Version number must be at least 1.");
        }
    }

    public static RegisterMediaAssetVersionConditionalResult unchanged(UUID assetId, int currentVersionNumber, Instant updatedAt) {
        return new RegisterMediaAssetVersionConditionalResult(
                assetId,
                null,
                currentVersionNumber,
                MediaVersionUploadOutcome.UNCHANGED,
                updatedAt
        );
    }

    public static RegisterMediaAssetVersionConditionalResult versionCreated(
            UUID assetId,
            UUID versionId,
            int newVersionNumber,
            Instant updatedAt
    ) {
        Objects.requireNonNull(versionId, "Version ID cannot be null for created version.");
        Objects.requireNonNull(updatedAt, "Updated at timestamp cannot be null for created version.");
        return new RegisterMediaAssetVersionConditionalResult(
                assetId,
                versionId,
                newVersionNumber,
                MediaVersionUploadOutcome.VERSION_CREATED,
                updatedAt
        );
    }
}
