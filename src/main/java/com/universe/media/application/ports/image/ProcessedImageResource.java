package com.universe.media.application.ports.image;

import com.universe.media.domain.MimeType;

import java.io.Closeable;
import java.io.InputStream;

/**
 * Caller-owned processed image resource containing metadata and stream access.
 * <p>
 * Implementations manage underlying temporary storage and must safely release
 * resources upon {@link #close()}.
 * <p>
 * <b>Stream Ownership:</b> Every {@link InputStream} returned by {@link #openStream()}
 * is caller-owned and must be closed before invoking {@link #close()} on this resource.
 */
public interface ProcessedImageResource extends Closeable {

    MimeType mimeType();

    long sizeBytes();

    int width();

    int height();

    /**
     * Opens a new readable input stream for the processed binary content.
     * <p>
     * The caller is responsible for closing the returned stream.
     *
     * @return an open {@link InputStream}
     * @throws IllegalStateException if this resource has already been closed
     */
    InputStream openStream();

    @Override
    void close();
}
