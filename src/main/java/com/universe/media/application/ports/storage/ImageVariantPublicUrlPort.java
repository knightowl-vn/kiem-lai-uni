package com.universe.media.application.ports.storage;

import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;

import java.net.URI;

/**
 * Application port for generating public CDN transformation URLs for image variants
 * without requiring direct infrastructure dependencies or byte downloads.
 */
public interface ImageVariantPublicUrlPort {

    /**
     * Checks if this port supports generating public variant URLs for the given storage provider.
     *
     * @param providerId the storage provider ID
     * @return true if supported, false otherwise
     */
    boolean supports(StorageProviderId providerId);

    /**
     * Generates a validated public delivery URL for a derivative image variant.
     *
     * @param sourceLocation the storage location of the source image version
     * @param spec           the variant specification (target width and variant key)
     * @return validated absolute HTTPS URI for the variant
     */
    URI generateVariantUrl(StorageLocation sourceLocation, ImageVariantSpec spec);
}
