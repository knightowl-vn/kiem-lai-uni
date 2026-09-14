package com.universe.novel.application.narration;

import java.io.InputStream;

/**
 * Caller-owned temporary binary chapter audio resource.
 */
public interface ChapterAudioBinaryResource extends AutoCloseable {

    String mimeType();

    long sizeBytes();

    /**
     * Opens a new readable stream for the audio binary.
     * <p>
     * The caller owns and must close the returned stream before closing this resource.
     */
    InputStream openStream();

    @Override
    void close();
}
