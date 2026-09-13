package com.universe.media.application.asset;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

public record UploadMediaAssetVersionCommand(
        UUID assetId,
        InputStream content,
        long sizeBytes,
        String mimeType,
        String originalFilename
) {
    public UploadMediaAssetVersionCommand {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        Objects.requireNonNull(content, "Content InputStream cannot be null.");
        Objects.requireNonNull(mimeType, "MimeType cannot be null.");

        if (sizeBytes <= 0) {
            throw new IllegalArgumentException(
                    "sizeBytes must be positive: " + sizeBytes
            );
        }
    }
}
