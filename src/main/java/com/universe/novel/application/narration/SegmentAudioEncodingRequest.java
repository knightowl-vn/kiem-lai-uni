package com.universe.novel.application.narration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Passive request model for segment narration audio encoding.
 */
public record SegmentAudioEncodingRequest(
        String mimeType,
        SegmentAudioBinarySource binarySource
) {
    public SegmentAudioEncodingRequest {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be blank");
        }
        mimeType = mimeType.trim();
        Objects.requireNonNull(binarySource, "binarySource must not be null");
    }

    public static SegmentAudioEncodingRequest of(String mimeType, byte[] audioBytes) {
        Objects.requireNonNull(audioBytes, "audioBytes must not be null");
        return new SegmentAudioEncodingRequest(mimeType, () -> new ByteArrayInputStream(audioBytes));
    }

    public InputStream openStream() throws IOException {
        return binarySource.openStream();
    }
}
