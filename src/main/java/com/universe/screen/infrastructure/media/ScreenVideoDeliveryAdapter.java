package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.screen.application.dto.ScreenPlaybackDeliveryInfo;
import com.universe.screen.application.ports.ScreenVideoDeliveryPort;
import com.universe.shared.exceptions.BaseApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing {@link ScreenVideoDeliveryPort} using Media module public contract {@link MediaContract}.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Asset must be ACTIVE in status;</li>
 *   <li>Asset must be PUBLIC in visibility;</li>
 *   <li>Asset must be VIDEO in media type;</li>
 *   <li>Asset must have an authoritative active version;</li>
 *   <li>Version MIME type must be canonical {@code video/mp4} under Screen V1 video policy;</li>
 *   <li>Catches expected missing-version failures ({@code MEDIA_ASSET_VERSION_NOT_FOUND}) via {@link BaseApplicationException}
 *       without swallowing unrelated runtime or infrastructure exceptions;</li>
 *   <li>Resolves canonical delivery URL via {@link MediaDeliveryUrlSupport};</li>
 *   <li>Zero leakage of storage provider keys, bucket identities, or cloud credentials.</li>
 * </ul>
 */
@Component
public class ScreenVideoDeliveryAdapter implements ScreenVideoDeliveryPort {

    private static final Logger log = LoggerFactory.getLogger(ScreenVideoDeliveryAdapter.class);

    public static final String CANONICAL_VIDEO_MIME_TYPE = "video/mp4";

    private final MediaContract mediaContract;

    public ScreenVideoDeliveryAdapter(MediaContract mediaContract) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
    }

    @Override
    public Optional<ScreenPlaybackDeliveryInfo> resolvePlaybackDelivery(UUID videoMediaAssetId) {
        if (videoMediaAssetId == null) {
            return Optional.empty();
        }

        Optional<MediaAssetDetailDTO> detailOpt;
        try {
            detailOpt = mediaContract.getAssetDetail(videoMediaAssetId);
        } catch (BaseApplicationException ex) {
            if ("MEDIA_ASSET_VERSION_NOT_FOUND".equals(ex.getErrorCode())
                    || "MEDIA_ASSET_NOT_FOUND".equals(ex.getErrorCode())) {
                log.warn("Expected media unavailable ({}) for video asset ID {}: {}",
                        ex.getErrorCode(), videoMediaAssetId, ex.getMessage());
                return Optional.empty();
            }
            throw ex;
        }

        if (detailOpt == null || detailOpt.isEmpty()) {
            return Optional.empty();
        }

        MediaAssetDetailDTO detail = detailOpt.get();
        if (detail.status() != MediaAssetStatusDTO.ACTIVE
                || detail.visibility() != MediaVisibilityDTO.PUBLIC
                || detail.mediaType() != MediaTypeDTO.VIDEO) {
            return Optional.empty();
        }

        MediaVersionDTO currentVersion = detail.currentVersion();
        if (currentVersion == null || currentVersion.mimeType() == null || currentVersion.mimeType().isBlank()) {
            return Optional.empty();
        }

        String normalizedMime = currentVersion.mimeType().trim().toLowerCase(Locale.ROOT);
        if (!CANONICAL_VIDEO_MIME_TYPE.equals(normalizedMime)) {
            log.warn("Unsupported video MIME type '{}' for media asset ID {}. Screen V1 requires canonical '{}'.",
                    normalizedMime, videoMediaAssetId, CANONICAL_VIDEO_MIME_TYPE);
            return Optional.empty();
        }

        String deliveryUrl = MediaDeliveryUrlSupport.contentUrl(videoMediaAssetId);
        return Optional.of(new ScreenPlaybackDeliveryInfo(videoMediaAssetId, deliveryUrl, normalizedMime));
    }
}
