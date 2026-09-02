package com.universe.media.application.asset;

import java.util.Objects;
import java.util.UUID;

public record GetMediaAssetContentQuery(
        UUID assetId
) {
    public GetMediaAssetContentQuery {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
    }
}
