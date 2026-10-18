package com.universe.media.contracts.dto;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * Public consumer-neutral DTO representing an active MediaAsset binary stream.
 *
 * <p><strong>Stream Ownership:</strong> The caller owns and must close the {@link #contentStream()}.
 * Implementing {@link Closeable} enables try-with-resources.
 */
public record MediaAssetStreamDTO(
        UUID assetId,
        int versionNumber,
        String mimeType,
        long totalSizeBytes,
        String contentHash,
        InputStream contentStream
) implements Closeable {

    public MediaAssetStreamDTO {
        Objects.requireNonNull(assetId, "assetId cannot be null.");
        Objects.requireNonNull(mimeType, "mimeType cannot be null.");
        Objects.requireNonNull(contentStream, "contentStream cannot be null.");
    }

    @Override
    public void close() throws IOException {
        if (contentStream != null) {
            contentStream.close();
        }
    }
}
