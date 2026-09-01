package com.universe.media.application.asset;

import java.time.Instant;
import java.util.UUID;

public record RegisterMediaAssetVersionResult(
        UUID assetId,
        UUID versionId,
        int newVersionNumber,
        Instant updatedAt
) {
}
