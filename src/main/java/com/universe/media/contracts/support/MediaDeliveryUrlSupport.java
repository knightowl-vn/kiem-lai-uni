package com.universe.media.contracts.support;

import java.util.UUID;

/**
 * Consumer-neutral helper for resolving standard Media delivery URLs.
 */
public final class MediaDeliveryUrlSupport {

    private MediaDeliveryUrlSupport() {
    }

    /**
     * Resolves the canonical content streaming URL for an asset's current version.
     *
     * @param assetId ID of the media asset
     * @return content URL, or {@code null} if assetId is null
     */
    public static String contentUrl(UUID assetId) {
        if (assetId == null) {
            return null;
        }
        return "/media/assets/" + assetId + "/content";
    }

    /**
     * Resolves the canonical image variant delivery URL for an asset's current version.
     *
     * @param assetId     ID of the media asset
     * @param targetWidth target width in pixels
     * @return variant URL, or {@code null} if assetId is null
     */
    public static String variantUrl(UUID assetId, int targetWidth) {
        if (assetId == null) {
            return null;
        }
        return "/media/assets/" + assetId + "/variants/w" + targetWidth;
    }
}
