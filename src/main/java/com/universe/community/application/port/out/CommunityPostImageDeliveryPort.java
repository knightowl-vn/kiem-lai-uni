package com.universe.community.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound port resolving direct public image delivery URLs for Community post attachments.
 *
 * <p>Implementations must fail closed: ineligible, non-public, non-image, or unresolvable assets
 * must simply be omitted from the returned map so callers fall back safely to standard content URLs.
 */
public interface CommunityPostImageDeliveryPort {

    /**
     * Resolves approved direct public image URLs for the given post image asset IDs in one batch.
     *
     * @param assetIds collection of Community post {@code imageMediaAssetId} values
     * @return map of eligible asset IDs to direct HTTPS delivery URLs (never null)
     */
    Map<UUID, String> resolvePublicImageUrls(Collection<UUID> assetIds);
}
