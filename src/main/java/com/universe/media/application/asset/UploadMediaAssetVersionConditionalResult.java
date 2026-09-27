package com.universe.media.application.asset;

import com.universe.media.contracts.dto.MediaVersionUploadOutcome;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Application result for conditional media asset version upload (MS-05G9).
 */
public record UploadMediaAssetVersionConditionalResult(
        UUID assetId,
        UUID versionId,
        int versionNumber,
        MediaVersionUploadOutcome outcome,
        Instant updatedAt
) {

    public UploadMediaAssetVersionConditionalResult {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        Objects.requireNonNull(outcome, "MediaVersionUploadOutcome cannot be null.");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("Version number must be at least 1.");
        }
    }

    public static UploadMediaAssetVersionConditionalResult unchanged(UUID assetId, int currentVersionNumber) {
        return new UploadMediaAssetVersionConditionalResult(
                assetId,
                null,
                currentVersionNumber,
                MediaVersionUploadOutcome.UNCHANGED,
                null
        );
    }

    public static UploadMediaAssetVersionConditionalResult versionCreated(
            UUID assetId,
            UUID versionId,
            int versionNumber,
            Instant updatedAt
    ) {
        return new UploadMediaAssetVersionConditionalResult(
                assetId,
                versionId,
                versionNumber,
                MediaVersionUploadOutcome.VERSION_CREATED,
                updatedAt
        );
    }
}
