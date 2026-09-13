package com.universe.novel.application.narration;

import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable resolution result containing derived audio health status and currently relevant failure diagnostics (MS-04.9H.8C2).
 *
 * @param status          derived health status (READY, OUTDATED, FAILED, MISSING)
 * @param relevantFailure failure diagnostic aggregate if relevant to current health resolution, or null if suppressed/absent
 */
public record ChapterNarrationAudioHealthResolution(
        ChapterNarrationAudioHealthStatus status,
        ChapterNarrationAudioFailure relevantFailure
) {
    public ChapterNarrationAudioHealthResolution {
        Objects.requireNonNull(status, "status must not be null");
    }

    /**
     * Indicates whether the failure diagnostic is relevant to current health resolution.
     */
    public boolean isFailureRelevant() {
        return relevantFailure != null;
    }

    /**
     * Indicates whether audio exists and is eligible for public playback (READY or OUTDATED).
     */
    public boolean isPlayable() {
        return status == ChapterNarrationAudioHealthStatus.READY
                || status == ChapterNarrationAudioHealthStatus.OUTDATED;
    }

    /**
     * Returns the optional relevant failure diagnostic.
     */
    public Optional<ChapterNarrationAudioFailure> optionalRelevantFailure() {
        return Optional.ofNullable(relevantFailure);
    }
}
