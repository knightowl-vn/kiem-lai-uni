package com.universe.novel.application.narration;

import java.io.InputStream;

/**
 * Caller-owned temporary chapter audio resource.
 */
public interface ChapterAudioAssemblyResource extends AutoCloseable {

    String mimeType();

    long sizeBytes();

    /**
     * Opens a new readable stream for the assembled audio.
     * <p>
     * The caller owns and must close the returned stream before closing this resource.
     */
    InputStream openStream();

    @Override
    void close();
}
