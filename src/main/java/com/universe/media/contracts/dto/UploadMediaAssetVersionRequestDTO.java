package com.universe.media.contracts.dto;

import java.io.InputStream;
import java.util.UUID;

/**
 * Public request DTO for uploading a new version of an existing media asset.
 *
 * <p><strong>Stream Ownership:</strong> The caller retains ownership of the {@code content}
 * {@link InputStream}. The Media module reads but does not close the stream; the caller is
 * responsible for closing it.
 */
public record UploadMediaAssetVersionRequestDTO(
        UUID assetId,
        InputStream content,
        long sizeBytes,
        String mimeType,
        String originalFilename
) {
}
