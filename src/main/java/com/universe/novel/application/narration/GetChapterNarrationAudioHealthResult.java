package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Result record containing the derived health status, assignment details, and last failure diagnostics.
 *
 * @param segmentId                  identity of the chapter narration segment
 * @param managedVoiceId             identity of the managed voice
 * @param status                     derived health status (MISSING, READY, OUTDATED, FAILED)
 * @param assignmentId               identity of the existing audio assignment, or null if missing
 * @param mediaAssetId               identity of the assigned Media asset, or null if missing
 * @param generatedSynthesisRevision synthesis revision at which current audio was generated, or null if missing
 * @param currentSynthesisRevision   current synthesis revision of the managed voice
 * @param lastFailure                latest unresolved failure diagnostics, or null if none recorded
 */
public record GetChapterNarrationAudioHealthResult(
        UUID segmentId,
        UUID managedVoiceId,
        ChapterNarrationAudioHealthStatus status,
        UUID assignmentId,
        UUID mediaAssetId,
        Long generatedSynthesisRevision,
        long currentSynthesisRevision,
        NarrationAudioFailureDiagnosticsDTO lastFailure
) {
    public GetChapterNarrationAudioHealthResult {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
