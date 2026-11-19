package com.universe.wiki.application.ports;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for public Wiki cover image URL delivery resolution.
 *
 * <p>Kept strictly decoupled from foreign storage engines, Cloudinary SDKs, or JPA entities.
 * Only standard Java types are declared across the port boundary.
 */
public interface WikiPublicCoverImageDeliveryPort {

    /**
     * Resolves direct public delivery URLs for a batch of cover image assets.
     *
     * @param assetIds collection of Media asset UUIDs to resolve
     * @param targetWidth preferred pixel width (e.g. 300)
     * @param fallbackToSource whether to fall back to the asset's source delivery URL if the target variant is unavailable
     * @return map of asset UUID to direct delivery URL (never null)
     */
    Map<UUID, String> resolvePublicCoverUrls(Collection<UUID> assetIds, Integer targetWidth, boolean fallbackToSource);

    /**
     * Resolves a direct public delivery URL for a single cover image asset.
     *
     * @param assetId Media asset UUID
     * @param targetWidth preferred pixel width (e.g. 300)
     * @param fallbackToSource whether to fall back to the asset's source delivery URL if the target variant is unavailable
     * @return Optional containing direct delivery URL if available, or Optional.empty()
     */
    Optional<String> resolvePublicCoverUrl(UUID assetId, Integer targetWidth, boolean fallbackToSource);
}
