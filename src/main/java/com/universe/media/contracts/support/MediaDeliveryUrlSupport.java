package com.universe.media.contracts.support;

import java.util.Optional;
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

    /**
     * Parses the asset ID from an exact canonical content streaming URL.
     *
     * <p>Recognizes only the exact canonical format produced by {@link #contentUrl(UUID)}:
     * {@code /media/assets/{assetId}/content} where {assetId} is a standard 36-character canonical UUID.
     *
     * @param url URL string to parse
     * @return Optional containing the UUID if the URL is an exact canonical content URL, empty otherwise
     */
    public static Optional<UUID> parseContentAssetId(String url) {
        if (url == null || url.length() != 58) {
            return Optional.empty();
        }
        if (!url.startsWith("/media/assets/") || !url.endsWith("/content")) {
            return Optional.empty();
        }
        String uuidString = url.substring(14, 50);
        try {
            UUID assetId = UUID.fromString(uuidString);
            if (!uuidString.equals(assetId.toString())) {
                return Optional.empty();
            }
            return Optional.of(assetId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

