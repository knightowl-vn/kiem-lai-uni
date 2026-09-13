package com.universe.novel.application.narration;

import java.util.Objects;

/**
 * Passive request model for chapter narration audio encoding.
 */
public record ChapterAudioEncodingRequest(
        ChapterAudioAssemblyResource source
) {
    public ChapterAudioEncodingRequest {
        Objects.requireNonNull(source, "source must not be null");
    }
}
