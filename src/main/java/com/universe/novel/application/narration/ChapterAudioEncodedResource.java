package com.universe.novel.application.narration;

import java.io.InputStream;

/**
 * Caller-owned temporary encoded chapter audio resource.
 */
public interface ChapterAudioEncodedResource extends AutoCloseable {

    String mimeType();

    long sizeBytes();

    /**
     * Opens a new readable stream for the encoded audio.
     * <p>
     * The caller owns and must close the returned stream before closing this resource.
     */
    InputStream openStream();

    @Override
    void close();
}
