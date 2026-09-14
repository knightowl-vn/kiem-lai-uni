package com.universe.novel.application.narration;

import java.io.InputStream;

/**
 * Caller-owned temporary encoded segment audio resource.
 */
public interface SegmentAudioEncodedResource extends AutoCloseable {

    String mimeType();

    long sizeBytes();

    long encodedContributionSamples();

    int sampleRateHz();

    /**
     * Opens a new readable stream for the encoded audio.
     * <p>
     * The caller owns and must close the returned stream before closing this resource.
     */
    InputStream openStream();

    @Override
    void close();
}
