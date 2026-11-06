package com.universe.screen.application.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Public application DTO representing resolved media playback delivery reference.
 *
 * <p>Preserves bounded-context purity: contains only the scalar asset ID and
 * canonical public delivery URL, avoiding any exposure of internal storage keys,
 * provider identities, or vendor buckets.
 */
public record ScreenPlaybackDeliveryInfo(
        UUID mediaAssetId,
        String deliveryUrl,
        String mimeType
) {

    public ScreenPlaybackDeliveryInfo {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");
        Objects.requireNonNull(deliveryUrl, "Delivery URL cannot be null.");
        Objects.requireNonNull(mimeType, "MIME type cannot be null.");
        if (mimeType.isBlank()) {
            throw new IllegalArgumentException("MIME type cannot be blank.");
        }
    }
}
