package com.universe.media.application.asset;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of a successful physical purge of an expired DELETED MediaAsset.
 */
public record PurgeDeletedMediaAssetResult(
        UUID assetId,
        int purgedVersionsCount,
        int purgedVariantsCount,
        Instant purgedAt
) {
}
