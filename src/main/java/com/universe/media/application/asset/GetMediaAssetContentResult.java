package com.universe.media.application.asset;

import java.io.InputStream;
import java.util.Objects;

/**
 * Result of retrieving binary content for a media asset.
 * <p>
 * <strong>Stream Ownership:</strong> The delivery/caller layer owns the returned {@link InputStream}
 * and is responsible for properly closing it after streaming or handling the response.
 */
public record GetMediaAssetContentResult(
        InputStream content,
        long sizeBytes,
        String mimeType,
        String contentHash
) {
    public GetMediaAssetContentResult {
        Objects.requireNonNull(content, "Content stream cannot be null.");
        Objects.requireNonNull(mimeType, "MIME type cannot be null.");
        Objects.requireNonNull(contentHash, "Content hash cannot be null.");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Size in bytes cannot be negative.");
        }
    }
}
