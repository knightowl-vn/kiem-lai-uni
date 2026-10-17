package com.universe.screen.application.ports;

import java.io.InputStream;
import java.util.UUID;

/**
 * Screen-owned outbound port for uploading and managing Screen image media assets (posters, backdrops).
 *
 * <p>Preserves bounded-context purity by using standard Java types without leaking
 * Media module internal storage abstractions or persistence models.
 */
public interface ScreenImageMediaPort {

    /**
     * Uploads and registers a poster image asset.
     *
     * @param inputStream binary content stream (caller retains ownership and closes)
     * @param sizeBytes length of the binary in bytes
     * @param contentType MIME type of the image (e.g. image/jpeg, image/png, image/webp)
     * @param originalFilename client-provided original filename
     * @param slugHint optional title slug hint for Cloudinary key naming
     * @return registered Media asset UUID
     */
    UUID uploadPoster(InputStream inputStream, long sizeBytes, String contentType, String originalFilename, String slugHint);

    /**
     * Uploads and registers a backdrop image asset.
     *
     * @param inputStream binary content stream (caller retains ownership and closes)
     * @param sizeBytes length of the binary in bytes
     * @param contentType MIME type of the image (e.g. image/jpeg, image/png, image/webp)
     * @param originalFilename client-provided original filename
     * @param slugHint optional title slug hint for Cloudinary key naming
     * @return registered Media asset UUID
     */
    UUID uploadBackdrop(InputStream inputStream, long sizeBytes, String contentType, String originalFilename, String slugHint);

    /**
     * Compensates a newly registered media asset when Screen persistence fails.
     *
     * @param assetId scalar UUID of the media asset to delete
     * @param primaryException the exception that triggered compensation (for addSuppressed)
     */
    void compensateUploadedImage(UUID assetId, Throwable primaryException);
}
