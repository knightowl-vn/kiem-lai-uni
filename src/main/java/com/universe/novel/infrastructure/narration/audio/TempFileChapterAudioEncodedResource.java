package com.universe.novel.infrastructure.narration.audio;

import com.universe.novel.application.narration.ChapterAudioEncodedResource;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Temporary file-backed encoded chapter audio resource.
 */
public final class TempFileChapterAudioEncodedResource implements ChapterAudioEncodedResource {

    @FunctionalInterface
    interface StreamOpener {
        InputStream open(Path path) throws IOException;
    }

    @FunctionalInterface
    interface TempFileDeleter {
        void deleteIfExists(Path path) throws IOException;
    }

    private final Path tempFile;
    private final String mimeType;
    private final long sizeBytes;
    private final StreamOpener streamOpener;
    private final TempFileDeleter tempFileDeleter;
    private final Object lock = new Object();
    private int activeStreams = 0;
    private boolean closed = false;

    public TempFileChapterAudioEncodedResource(Path tempFile, String mimeType, long sizeBytes) {
        this(tempFile, mimeType, sizeBytes, Files::newInputStream, Files::deleteIfExists);
    }

    TempFileChapterAudioEncodedResource(
            Path tempFile,
            String mimeType,
            long sizeBytes,
            StreamOpener streamOpener,
            TempFileDeleter tempFileDeleter
    ) {
        this.tempFile = Objects.requireNonNull(tempFile, "tempFile must not be null");
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be blank");
        }
        this.mimeType = mimeType.trim();
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be > 0: " + sizeBytes);
        }
        this.sizeBytes = sizeBytes;
        this.streamOpener = Objects.requireNonNull(streamOpener, "streamOpener must not be null");
        this.tempFileDeleter = Objects.requireNonNull(tempFileDeleter, "tempFileDeleter must not be null");
    }

    @Override
    public String mimeType() {
        return mimeType;
    }

    @Override
    public long sizeBytes() {
        return sizeBytes;
    }

    @Override
    public InputStream openStream() {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("ChapterAudioEncodedResource has already been closed.");
            }
            try {
                InputStream rawStream = streamOpener.open(tempFile);
                if (rawStream == null) {
                    throw new IllegalStateException("Encoded audio stream opener returned null.");
                }
                activeStreams++;
                return new TrackedInputStream(rawStream);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to open encoded chapter audio temp file.", e);
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
                throw new IllegalStateException(
                        "Cannot close resource: " + activeStreams + " open stream(s) returned by openStream() must be closed first."
                );
            }
            try {
                tempFileDeleter.deleteIfExists(tempFile);
                closed = true;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to delete temporary encoded chapter audio resource.", e);
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
                streamClosed = true;
                try {
                    in.close();
                } finally {
                    synchronized (lock) {
                        activeStreams--;
                    }
                }
            }
        }
    }
}
