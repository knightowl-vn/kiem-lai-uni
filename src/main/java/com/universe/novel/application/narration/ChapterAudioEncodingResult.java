package com.universe.novel.application.narration;

import java.io.InputStream;
import java.util.Objects;

/**
 * Caller-owned chapter audio encoding output.
 */
public record ChapterAudioEncodingResult(
        ChapterAudioEncodedResource resource
) implements AutoCloseable {
    public ChapterAudioEncodingResult {
        Objects.requireNonNull(resource, "resource must not be null");
    }

    public String mimeType() {
        return resource.mimeType();
    }

    public long sizeBytes() {
        return resource.sizeBytes();
    }

    /**
     * Opens a caller-owned stream for the encoded audio.
     */
    public InputStream openStream() {
        return resource.openStream();
    }

    @Override
    public void close() {
        resource.close();
    }
}
