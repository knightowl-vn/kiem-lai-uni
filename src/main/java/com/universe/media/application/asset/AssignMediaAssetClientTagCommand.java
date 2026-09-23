package com.universe.media.application.asset;

import java.util.Objects;
import java.util.UUID;

public record AssignMediaAssetClientTagCommand(
        UUID assetId,
        String clientTag
) {
    public AssignMediaAssetClientTagCommand {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        Objects.requireNonNull(clientTag, "Client tag cannot be null.");
    }
}
