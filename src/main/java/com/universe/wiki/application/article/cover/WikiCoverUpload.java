package com.universe.wiki.application.article.cover;

import java.io.InputStream;
import java.util.Objects;

/**
 * Protocol-neutral transfer object for cover image upload binary stream.
 *
 * <p><strong>Stream Ownership:</strong> The caller/controller owns and closes the {@link InputStream}.
 * The application consumes it synchronously.
 */
public record WikiCoverUpload(
        InputStream content,
        long sizeBytes,
        String contentType,
        String originalFilename
) {
    public WikiCoverUpload {
        Objects.requireNonNull(content, "Content InputStream cannot be null.");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Upload sizeBytes must be greater than 0.");
        }
        Objects.requireNonNull(contentType, "Content type cannot be null.");
        Objects.requireNonNull(originalFilename, "Original filename cannot be null.");
    }
}
