package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.ports.ScreenCatalogImageDeliveryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter implementing {@link ScreenCatalogImageDeliveryPort} using {@link MediaContract}.
 *
 * <p>Preserves bounded-context isolation: keeps foreign media storage logic decoupled from Screen,
 * and supports an operational kill-switch ({@code screen.catalog-image.direct-delivery-enabled})
 * defaulting to {@code true}.
 */
@Component
public class ScreenCatalogImageDeliveryAdapter implements ScreenCatalogImageDeliveryPort {

    private final MediaContract mediaContract;
    private final boolean directDeliveryEnabled;

    @Autowired
    public ScreenCatalogImageDeliveryAdapter(
            MediaContract mediaContract,
            @Value("${screen.catalog-image.direct-delivery-enabled:true}") boolean directDeliveryEnabled
    ) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
        this.directDeliveryEnabled = directDeliveryEnabled;
    }

    public ScreenCatalogImageDeliveryAdapter(MediaContract mediaContract) {
        this(mediaContract, true);
    }

    @Override
    public Map<UUID, String> resolvePublicImageUrls(
            Collection<UUID> assetIds,
            Integer targetWidth,
            boolean fallbackToSource
    ) {
        if (!directDeliveryEnabled || assetIds == null || assetIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<UUID, String> resolved = mediaContract.resolvePublicImageDeliveryUrls(assetIds, targetWidth, fallbackToSource);
        return resolved != null ? resolved : Collections.emptyMap();
    }
}
