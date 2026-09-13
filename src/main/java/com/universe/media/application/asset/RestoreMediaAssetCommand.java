package com.universe.media.application.asset;

import java.util.UUID;

public record RestoreMediaAssetCommand(
        UUID assetId
) {
}
