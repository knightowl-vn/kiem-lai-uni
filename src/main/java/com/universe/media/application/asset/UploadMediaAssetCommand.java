package com.universe.media.application.asset;

import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;

import java.io.InputStream;
import java.util.Objects;

public record UploadMediaAssetCommand(
        InputStream content,
        long sizeBytes,
        String mimeType,
        MediaType mediaType,
        MediaVisibility visibility,
        String originalFilename
) {
    public UploadMediaAssetCommand {
        Objects.requireNonNull(content, "Content InputStream cannot be null.");
        Objects.requireNonNull(mimeType, "MimeType cannot be null.");
        Objects.requireNonNull(mediaType, "MediaType cannot be null.");
        Objects.requireNonNull(visibility, "MediaVisibility cannot be null.");

        if (sizeBytes <= 0) {
            throw new IllegalArgumentException(
                    "sizeBytes must be positive: " + sizeBytes
            );
        }
    }
}
