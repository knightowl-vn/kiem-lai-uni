package com.universe.media.application.asset;

import java.util.UUID;

/**
 * Command to execute physical purge of an expired DELETED MediaAsset.
 */
public record PurgeDeletedMediaAssetCommand(
        UUID assetId
) {
}
