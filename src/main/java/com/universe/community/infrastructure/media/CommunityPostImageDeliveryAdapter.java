package com.universe.community.infrastructure.media;

import com.universe.community.application.port.out.CommunityPostImageDeliveryPort;
import com.universe.media.contracts.interfaces.MediaContract;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter implementing {@link CommunityPostImageDeliveryPort} using {@link MediaContract}.
 *
 * <p>Preserves bounded-context isolation between Community and Media and supports an operational
 * kill-switch ({@code community.post-image.direct-delivery-enabled}) defaulting to {@code true}.
 */
@Component
public class CommunityPostImageDeliveryAdapter implements CommunityPostImageDeliveryPort {

    private final MediaContract mediaContract;
    private final boolean directDeliveryEnabled;

    @Autowired
    public CommunityPostImageDeliveryAdapter(
            MediaContract mediaContract,
            @Value("${community.post-image.direct-delivery-enabled:true}") boolean directDeliveryEnabled
    ) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
        this.directDeliveryEnabled = directDeliveryEnabled;
    }

    public CommunityPostImageDeliveryAdapter(MediaContract mediaContract) {
        this(mediaContract, true);
    }

    @Override
    public Map<UUID, String> resolvePublicImageUrls(Collection<UUID> assetIds) {
        if (!directDeliveryEnabled || assetIds == null || assetIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<UUID, String> resolved = mediaContract.resolvePublicImageDeliveryUrls(assetIds, (Integer) null, true);
        return resolved != null ? resolved : Collections.emptyMap();
    }
}
