package com.universe.media.contracts.dto;

import java.io.InputStream;

/**
 * Public request DTO for uploading a new media asset.
 *
 * <p><strong>Stream Ownership:</strong> The caller retains ownership of the {@code content}
 * {@link InputStream}. The Media module reads but does not close the stream; the caller is
 * responsible for closing it.
 */
public record UploadMediaAssetRequestDTO(
        InputStream content,
        long sizeBytes,
        String mimeType,
        MediaTypeDTO mediaType,
        MediaVisibilityDTO visibility,
        String originalFilename,
        String clientTag
) {
    /**
     * Backward-compatible constructor defaulting {@code clientTag} to {@code null}.
     */
    public UploadMediaAssetRequestDTO(
            InputStream content,
            long sizeBytes,
            String mimeType,
            MediaTypeDTO mediaType,
            MediaVisibilityDTO visibility,
            String originalFilename
    ) {
        this(content, sizeBytes, mimeType, mediaType, visibility, originalFilename, null);
    }
}
