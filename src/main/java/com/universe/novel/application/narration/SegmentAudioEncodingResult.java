package com.universe.novel.application.narration;

import java.io.InputStream;
import java.util.Objects;

/**
 * Caller-owned segment audio encoding output.
 */
public record SegmentAudioEncodingResult(
        SegmentAudioEncodedResource resource
) implements AutoCloseable {
    public SegmentAudioEncodingResult {
        Objects.requireNonNull(resource, "resource must not be null");
    }

    public String mimeType() {
        return resource.mimeType();
    }

    public long sizeBytes() {
        return resource.sizeBytes();
    }

    public long encodedContributionSamples() {
        return resource.encodedContributionSamples();
    }

    public int sampleRateHz() {
        return resource.sampleRateHz();
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
