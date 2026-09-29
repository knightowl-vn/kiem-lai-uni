package com.universe.media.application.asset;

import java.io.InputStream;
import java.net.URI;
import java.util.Objects;

/**
 * Result of retrieving binary content for a media asset or variant.
 * <p>
 * Supports both streaming delivery (via {@link #content()}) and external redirect delivery (via {@link #publicUrl()}).
 * <p>
 * <strong>Stream Ownership:</strong> When {@link #content()} is present, the delivery/caller layer owns the returned
 * {@link InputStream} and is responsible for properly closing it after streaming or handling the response.
 */
public record GetMediaAssetContentResult(
        InputStream content,
        long sizeBytes,
        String mimeType,
        String contentHash,
        String publicUrl
) {

    public GetMediaAssetContentResult(
            InputStream content,
            long sizeBytes,
            String mimeType,
            String contentHash
    ) {
        this(content, sizeBytes, mimeType, contentHash, null);
    }

    public GetMediaAssetContentResult {
        Objects.requireNonNull(mimeType, "MIME type cannot be null.");
        if (content != null) {
            Objects.requireNonNull(contentHash, "Content hash cannot be null for streaming content.");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Size in bytes cannot be negative.");
        }
        if (content == null && (publicUrl == null || publicUrl.isBlank())) {
            throw new IllegalArgumentException("Either content stream or publicUrl must be non-null.");
        }
        if (publicUrl != null && !publicUrl.isBlank()) {
            validateHttpsUri(publicUrl);
        }
    }

    public static GetMediaAssetContentResult stream(
            InputStream content,
            long sizeBytes,
            String mimeType,
            String contentHash
    ) {
        return new GetMediaAssetContentResult(content, sizeBytes, mimeType, contentHash, null);
    }

    public static GetMediaAssetContentResult redirect(
            String publicUrl,
            String mimeType
    ) {
        validateHttpsUri(publicUrl);
        return new GetMediaAssetContentResult(null, 0L, mimeType, null, publicUrl.trim());
    }

    public static GetMediaAssetContentResult redirect(
            String publicUrl,
            long sizeBytes,
            String mimeType,
            String contentHash
    ) {
        validateHttpsUri(publicUrl);
        return new GetMediaAssetContentResult(null, sizeBytes, mimeType, contentHash, publicUrl.trim());
    }

    public static URI validateHttpsUri(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("External delivery URL must not be blank.");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid external delivery URI: " + url, e);
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("External delivery URI must be absolute: " + url);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("External delivery URI must use HTTPS scheme: " + url);
        }
        return uri;
    }

    public boolean isRedirect() {
        return publicUrl != null && !publicUrl.isBlank();
    }

    public URI redirectUri() {
        return isRedirect() ? validateHttpsUri(publicUrl) : null;
    }
}
