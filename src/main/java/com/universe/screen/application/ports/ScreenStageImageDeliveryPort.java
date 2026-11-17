package com.universe.screen.application.ports;

import java.util.Optional;
import java.util.UUID;

/**
 * Screen-owned outbound port for resolving direct public image delivery URLs (pilot).
 *
 * <p>Preserves bounded-context isolation: relies exclusively on standard Java types,
 * without leaking Media module internal storage adapters, JPA entities, or persistence models.
 */
public interface ScreenStageImageDeliveryPort {

    /**
     * Resolves an approved direct public delivery URL for an eligible public media image asset or variant.
     *
     * @param assetId scalar UUID of the media asset
     * @param targetWidth optional target width in pixels (e.g. 600 for w600 variant), or null for source image
     * @return Optional containing the direct public HTTPS URL if eligible, empty otherwise
     */
    Optional<String> resolvePublicImageUrl(UUID assetId, Integer targetWidth);

    /**
     * Resolves an approved direct public delivery URL for an eligible public media image asset or variant,
     * with optional fallback to source image if the requested variant is missing.
     *
     * @param assetId scalar UUID of the media asset
     * @param targetWidth optional target width in pixels (e.g. 1200 for w1200 variant), or null for source image
     * @param fallbackToSource whether to fall back to current version source URL if target variant is missing
     * @return Optional containing the direct public HTTPS URL if eligible, empty otherwise
     */
    default Optional<String> resolvePublicImageUrl(UUID assetId, Integer targetWidth, boolean fallbackToSource) {
        return resolvePublicImageUrl(assetId, targetWidth);
    }
}
