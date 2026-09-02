package com.universe.media.contracts.dto;

import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;

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
        MediaType mediaType,
        MediaVisibility visibility,
        String originalFilename
) {
}
