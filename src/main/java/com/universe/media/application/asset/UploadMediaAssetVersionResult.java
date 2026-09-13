package com.universe.media.application.asset;

import java.time.Instant;
import java.util.UUID;

public record UploadMediaAssetVersionResult(
        UUID assetId,
        UUID versionId,
        int versionNumber,
        Instant registeredAt
) {
}
