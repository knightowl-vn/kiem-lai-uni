package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.dto.GenerateImageVariantRequestDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.ports.ScreenImageMediaPort;
import com.universe.screen.domain.ScreenSlugGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter implementing {@link ScreenImageMediaPort} using Media module public contract {@link MediaContract}.
 *
 * <p>Enforces isolation from Media module internal persistence or domain models.
 * Routes posters and backdrops with human-readable client tags for Cloudinary folder organization.
 */
@Component
public class ScreenImageMediaAdapter implements ScreenImageMediaPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenImageMediaAdapter.class);

    public static final String SCREEN_POSTER_TAG_PREFIX = "screen.poster";
    public static final String SCREEN_BACKDROP_TAG_PREFIX = "screen.backdrop";
    private static final int POSTER_VARIANT_WIDTH = 300;

    private final MediaContract mediaContract;

    public ScreenImageMediaAdapter(MediaContract mediaContract) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
    }

    @Override
    public UUID uploadPoster(InputStream inputStream, long sizeBytes, String contentType, String originalFilename, String slugHint) {
        String clientTag = buildClientTag(SCREEN_POSTER_TAG_PREFIX, slugHint);
        return uploadImage(inputStream, sizeBytes, contentType, originalFilename, clientTag, true);
    }

    @Override
    public UUID uploadBackdrop(InputStream inputStream, long sizeBytes, String contentType, String originalFilename, String slugHint) {
        String clientTag = buildClientTag(SCREEN_BACKDROP_TAG_PREFIX, slugHint);
        return uploadImage(inputStream, sizeBytes, contentType, originalFilename, clientTag, false);
    }

    private UUID uploadImage(InputStream inputStream, long sizeBytes, String contentType, String originalFilename, String clientTag, boolean generateVariant) {
        Objects.requireNonNull(inputStream, "InputStream cannot be null.");

        UploadMediaAssetRequestDTO request = new UploadMediaAssetRequestDTO(
                inputStream,
                sizeBytes,
                contentType,
                MediaTypeDTO.IMAGE,
                MediaVisibilityDTO.PUBLIC,
                originalFilename != null && !originalFilename.isBlank() ? originalFilename : "image.jpg",
                clientTag
        );

        UploadMediaAssetResponseDTO response = mediaContract.uploadAsset(request);
        UUID assetId = response.assetId();

        if (generateVariant && assetId != null) {
            generateVariantBestEffort(assetId);
        }

        return assetId;
    }

    private void generateVariantBestEffort(UUID assetId) {
        try {
            mediaContract.generateImageVariant(new GenerateImageVariantRequestDTO(assetId, POSTER_VARIANT_WIDTH));
        } catch (RuntimeException ex) {
            LOGGER.warn("Failed to generate best-effort image variant w{} for Screen media asset [{}]: {}",
                    POSTER_VARIANT_WIDTH, assetId, ex.getMessage());
        }
    }

    @Override
    public void compensateUploadedImage(UUID assetId, Throwable primaryException) {
        if (assetId == null) {
            return;
        }
        try {
            mediaContract.delete(assetId);
            LOGGER.info("Compensated (deleted) orphan Screen media asset [{}] after persistence failure.", assetId);
        } catch (RuntimeException compEx) {
            LOGGER.error("Failed to compensate Screen media asset [{}] after persistence failure: {}",
                    assetId, compEx.getMessage(), compEx);
            if (primaryException != null) {
                primaryException.addSuppressed(compEx);
            }
        }
    }

    private static String buildClientTag(String prefix, String slugHint) {
        if (slugHint == null || slugHint.isBlank()) {
            return prefix;
        }
        String sanitized = ScreenSlugGenerator.generate(slugHint);
        if (sanitized.length() > 40) {
            sanitized = sanitized.substring(0, 40).replaceAll("-+$", "");
        }
        if (sanitized.isBlank()) {
            return prefix;
        }
        return prefix + ":" + sanitized;
    }
}
