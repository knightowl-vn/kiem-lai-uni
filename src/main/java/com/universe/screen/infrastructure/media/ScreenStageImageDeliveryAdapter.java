package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.ports.ScreenStageImageDeliveryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing {@link ScreenStageImageDeliveryPort} using {@link MediaContract}.
 *
 * <p>Preserves bounded-context isolation: keeps foreign media storage logic decoupled from Screen,
 * and supports an operational kill-switch ({@code screen.stage-image.direct-delivery-enabled})
 * defaulting to {@code true}.
 */
@Component
public class ScreenStageImageDeliveryAdapter implements ScreenStageImageDeliveryPort {

    private final MediaContract mediaContract;
    private final boolean directDeliveryEnabled;

    @Autowired
    public ScreenStageImageDeliveryAdapter(
            MediaContract mediaContract,
            @Value("${screen.stage-image.direct-delivery-enabled:true}") boolean directDeliveryEnabled
    ) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
        this.directDeliveryEnabled = directDeliveryEnabled;
    }

    public ScreenStageImageDeliveryAdapter(MediaContract mediaContract) {
        this(mediaContract, true);
    }

    @Override
    public Optional<String> resolvePublicImageUrl(UUID assetId, Integer targetWidth) {
        if (!directDeliveryEnabled || assetId == null) {
            return Optional.empty();
        }
        return mediaContract.resolvePublicImageDeliveryUrl(assetId, targetWidth);
    }
}
