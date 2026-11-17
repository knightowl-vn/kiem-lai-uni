package com.universe.screen.application.ports;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Screen-owned outbound port for resolving direct public image delivery URLs in batch for catalog browsing.
 *
 * <p>Preserves bounded-context isolation: relies exclusively on standard Java types,
 * without leaking Media module internal storage adapters, JPA entities, or persistence models.
 */
public interface ScreenCatalogImageDeliveryPort {

    /**
     * Resolves approved direct public delivery URLs for a batch of eligible public media image assets or variants,
     * with optional fallback to source image if the requested variant is missing.
     *
     * @param assetIds collection of scalar UUIDs of the media assets
     * @param targetWidth optional target width in pixels (e.g. 300 for w300 variant), or null for source image
     * @param fallbackToSource whether to fall back to current version source URL if target variant is missing
     * @return map of eligible asset IDs to their direct public HTTPS URLs
     */
    Map<UUID, String> resolvePublicImageUrls(
            Collection<UUID> assetIds,
            Integer targetWidth,
            boolean fallbackToSource
    );
}
