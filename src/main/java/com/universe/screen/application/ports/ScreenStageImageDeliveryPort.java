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
}
