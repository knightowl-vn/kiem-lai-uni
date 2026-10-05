package com.universe.community.application.usecase;

import com.universe.community.domain.exception.CommunityPostValidationException;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Application service orchestrating the validation, local spooling, and upload of Community post images.
 */
@Service
public class CommunityPostImageUploadUseCase {

    private static final Logger log = LoggerFactory.getLogger(CommunityPostImageUploadUseCase.class);

    public static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024; // 10 MiB

    public static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    public static final String CLIENT_TAG = "community.post.image";

    private final MediaContract mediaContract;

    public CommunityPostImageUploadUseCase(MediaContract mediaContract) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
    }

    /**
     * Validates, spools, and uploads an image for a Community post.
     *
     * @param content the raw input stream of the image
     * @param declaredSizeBytes the declared size in bytes from the client
     * @param contentType the declared content type
     * @param originalFilename the original filename
     * @return the UUID of the newly registered Media asset
     */
    public UUID uploadImage(
            InputStream content,
            long declaredSizeBytes,
            String contentType,
            String originalFilename
    ) {
        validateMetadata(content, declaredSizeBytes, contentType);

        Path spoolFile = null;
        try {
            try {
                spoolFile = Files.createTempFile("community-post-spool-", ".tmp");
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create temporary spool file for community post image upload.", e);
            }

            long actualSizeBytes = 0;
            try (OutputStream out = Files.newOutputStream(spoolFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = content.read(buffer)) != -1) {
                    actualSizeBytes += bytesRead;
                    if (actualSizeBytes > MAX_IMAGE_SIZE_BYTES) {
                        throw new CommunityPostValidationException(
                                "Image file size (" + actualSizeBytes + " bytes) exceeds maximum limit of "
                                        + MAX_IMAGE_SIZE_BYTES + " bytes (10 MB)."
                        );
                    }
                    out.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write temporary spool file for community post image upload.", e);
            }

            if (actualSizeBytes == 0) {
                throw new CommunityPostValidationException("Image file cannot be empty.");
            }

            String normalizedContentType = contentType.trim().toLowerCase(Locale.ROOT);
            String normalizedFilename = normalizeFilename(originalFilename);

            UploadMediaAssetResponseDTO mediaResponse;
            try (InputStream uploadIn = Files.newInputStream(spoolFile)) {
                UploadMediaAssetRequestDTO uploadRequest = new UploadMediaAssetRequestDTO(
                        uploadIn,
                        actualSizeBytes,
                        normalizedContentType,
                        MediaTypeDTO.IMAGE,
                        MediaVisibilityDTO.PUBLIC,
                        normalizedFilename,
                        CLIENT_TAG
                );
                mediaResponse = mediaContract.uploadAsset(uploadRequest);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read temporary spool file for Media upload.", e);
            }

            return mediaResponse.assetId();

        } finally {
            if (spoolFile != null) {
                try {
                    Files.deleteIfExists(spoolFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Compensates a successful Media upload when subsequent post creation fails.
     *
     * @param mediaAssetId the uploaded media asset ID
     * @param primaryException the original exception causing the post creation failure
     */
    public void compensateUpload(UUID mediaAssetId, RuntimeException primaryException) {
        if (mediaAssetId == null) {
            return;
        }
        try {
            mediaContract.delete(mediaAssetId);
        } catch (RuntimeException compEx) {
            log.warn("Failed to compensate uploaded media asset [{}] after post creation failure.", mediaAssetId, compEx);
            if (primaryException != null) {
                primaryException.addSuppressed(compEx);
            }
        }
    }

    private void validateMetadata(
            InputStream content,
            long declaredSizeBytes,
            String contentType
    ) {
        if (content == null) {
            throw new CommunityPostValidationException("Image content stream cannot be null.");
        }

        if (declaredSizeBytes <= 0) {
            throw new CommunityPostValidationException("Image file cannot be empty.");
        }

        if (declaredSizeBytes > MAX_IMAGE_SIZE_BYTES) {
            throw new CommunityPostValidationException(
                    "Declared image size (" + declaredSizeBytes + " bytes) exceeds maximum limit of "
                            + MAX_IMAGE_SIZE_BYTES + " bytes (10 MB)."
            );
        }

        if (contentType == null || !SUPPORTED_MIME_TYPES.contains(contentType.trim().toLowerCase(Locale.ROOT))) {
            throw new CommunityPostValidationException(
                    "Unsupported image content type: " + contentType + ". Only JPG, PNG, and WEBP images are supported."
            );
        }
    }

    private String normalizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "community-image";
        }
        String normalized = originalFilename.trim().replace('\\', '/');
        int lastSlashIndex = normalized.lastIndexOf('/');
        if (lastSlashIndex >= 0 && lastSlashIndex < normalized.length() - 1) {
            normalized = normalized.substring(lastSlashIndex + 1);
        }
        return normalized;
    }
}
