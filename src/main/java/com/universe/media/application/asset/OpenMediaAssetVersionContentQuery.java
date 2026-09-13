package com.universe.media.application.asset;

import java.util.UUID;

public record OpenMediaAssetVersionContentQuery(
        UUID assetId,
        int versionNumber,
        String contentHash
) {
}
