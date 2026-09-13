package com.universe.media.infrastructure.image;

import com.universe.media.application.exceptions.ImageProcessingException;
import com.universe.media.application.ports.image.ProcessedImageResource;
import com.universe.media.domain.MimeType;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Temporary file-backed implementation of {@link ProcessedImageResource}.
 * <p>
 * Lifecycle Semantics:
 * <ul>
 *     <li>Streams returned by {@link #openStream()} are caller-owned and must be closed before closing this resource.</li>
 *     <li>Attempting to close while streams remain open throws an {@link ImageProcessingException} without marking closed.</li>
 *     <li>Once all open streams are closed, {@link #close()} can be retried to delete the backing file.</li>
 *     <li>{@link #close()} is idempotent once the file is successfully deleted.</li>
 *     <li>{@link #openStream()} throws {@link IllegalStateException} if invoked after successful close.</li>
 * </ul>
 */
public final class TempFileProcessedImageResource implements ProcessedImageResource {

    @FunctionalInterface
    interface StreamOpener {
        InputStream open(Path path) throws IOException;
    }

    private final Path tempFile;
    private final MimeType mimeType;
    private final long sizeBytes;
    private final int width;
    private final int height;
    private final StreamOpener streamOpener;
    private final Object lock = new Object();
    private int activeStreams = 0;
    private boolean closed = false;

    public TempFileProcessedImageResource(
            Path tempFile,
            MimeType mimeType,
            long sizeBytes,
            int width,
            int height
    ) {
        this(tempFile, mimeType, sizeBytes, width, height, Files::newInputStream);
    }

    TempFileProcessedImageResource(
            Path tempFile,
            MimeType mimeType,
            long sizeBytes,
            int width,
            int height,
            StreamOpener streamOpener
    ) {
        this.tempFile = Objects.requireNonNull(tempFile, "Temp file cannot be null.");
        this.mimeType = Objects.requireNonNull(mimeType, "MimeType cannot be null.");
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.streamOpener = Objects.requireNonNull(streamOpener, "StreamOpener cannot be null.");
    }

    @Override
    public MimeType mimeType() {
        return mimeType;
    }

    @Override
    public long sizeBytes() {
        return sizeBytes;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public InputStream openStream() {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("ProcessedImageResource has already been closed.");
            }
            try {
                InputStream rawStream = streamOpener.open(tempFile);
                activeStreams++;
                return new TrackedInputStream(rawStream);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to open input stream for processed image temp file.", e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (activeStreams > 0) {
                throw new ImageProcessingException(
                        "Cannot close resource: " + activeStreams + " open stream(s) returned by openStream() must be closed first."
                );
            }
            try {
                Files.deleteIfExists(tempFile);
                closed = true;
            } catch (IOException e) {
                throw new ImageProcessingException(
                        "Failed to delete temporary image resource file: " + tempFile,
                        e
                );
            }
        }
    }

    private final class TrackedInputStream extends FilterInputStream {

        private boolean streamClosed = false;

        private TrackedInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            synchronized (this) {
                if (streamClosed) {
                    return;
                }
                in.close();
                streamClosed = true;
            }
            synchronized (lock) {
                activeStreams--;
            }
        }
    }
}
