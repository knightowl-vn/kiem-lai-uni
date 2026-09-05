package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Query parameters for resolving a chapter narration audio assignment.
 */
public record GetChapterNarrationAudioAssignmentQuery(
        UUID segmentId,
        UUID managedVoiceId,
        long currentSynthesisRevision
) {

    public GetChapterNarrationAudioAssignmentQuery {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        if (currentSynthesisRevision < 1) {
            throw new IllegalArgumentException("currentSynthesisRevision must be at least 1: " + currentSynthesisRevision);
        }
    }
}
