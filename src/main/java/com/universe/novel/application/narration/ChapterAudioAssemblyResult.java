package com.universe.novel.application.narration;

import java.util.List;
import java.util.Objects;

/**
 * Caller-owned chapter audio assembly output.
 */
public record ChapterAudioAssemblyResult(
        ChapterAudioAssemblyResource resource,
        long durationMillis,
        List<ChapterAudioAssemblyCue> cues
) implements AutoCloseable {
    public ChapterAudioAssemblyResult {
        Objects.requireNonNull(resource, "resource must not be null");
        if (durationMillis <= 0) {
            throw new IllegalArgumentException("durationMillis must be > 0: " + durationMillis);
        }
        Objects.requireNonNull(cues, "cues must not be null");
        if (cues.isEmpty()) {
            throw new IllegalArgumentException("cues must not be empty");
        }
        cues = List.copyOf(cues);
        validateCueTimeline(durationMillis, cues);
    }

    @Override
    public void close() {
        resource.close();
    }

    private static void validateCueTimeline(long durationMillis, List<ChapterAudioAssemblyCue> cues) {
        long previousEndMillis = 0L;
        for (int i = 0; i < cues.size(); i++) {
            ChapterAudioAssemblyCue cue = Objects.requireNonNull(cues.get(i), "cues must not contain null elements");
            if (cue.cueOrdinal() != i) {
                throw new IllegalArgumentException("cueOrdinal must be contiguous from 0");
            }
            if (i > 0 && cue.startMillis() < previousEndMillis) {
                throw new IllegalArgumentException("Cue timeline must not overlap");
            }
            if (cue.endMillis() > durationMillis) {
                throw new IllegalArgumentException("Cue endMillis must be <= durationMillis");
            }
            previousEndMillis = cue.endMillis();
        }
    }
}
