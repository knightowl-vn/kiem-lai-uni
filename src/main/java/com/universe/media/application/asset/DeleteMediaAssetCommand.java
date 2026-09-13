package com.universe.media.application.asset;

import java.util.UUID;

public record DeleteMediaAssetCommand(
        UUID assetId
) {
}
