package com.universe.wiki.infrastructure.media;

import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.wiki.application.ports.WikiPublicCoverImageDeliveryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing {@link WikiPublicCoverImageDeliveryPort} via {@link MediaContract}.
 *
 * <p>Preserves bounded-context isolation: keeps foreign media storage logic decoupled from Wiki,
 * and supports an independent operational kill-switch ({@code wiki.public-cover.direct-delivery-enabled})
 * defaulting to {@code true}.
 */
@Component
public class WikiPublicCoverImageDeliveryAdapter implements WikiPublicCoverImageDeliveryPort {

    private final MediaContract mediaContract;
    private final boolean directDeliveryEnabled;

    @Autowired
    public WikiPublicCoverImageDeliveryAdapter(
            MediaContract mediaContract,
            @Value("${wiki.public-cover.direct-delivery-enabled:true}") boolean directDeliveryEnabled
    ) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
        this.directDeliveryEnabled = directDeliveryEnabled;
    }

    public WikiPublicCoverImageDeliveryAdapter(MediaContract mediaContract) {
        this(mediaContract, true);
    }

    @Override
    public Map<UUID, String> resolvePublicCoverUrls(
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

    @Override
    public Optional<String> resolvePublicCoverUrl(
            UUID assetId,
            Integer targetWidth,
            boolean fallbackToSource
    ) {
        if (!directDeliveryEnabled || assetId == null) {
            return Optional.empty();
        }
        Optional<String> resolved = mediaContract.resolvePublicImageDeliveryUrl(assetId, targetWidth, fallbackToSource);
        return resolved != null ? resolved : Optional.empty();
    }
}
