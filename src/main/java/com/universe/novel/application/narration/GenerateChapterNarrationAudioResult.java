package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Result record returned after processing a narration audio generation request.
 *
 * @param assignmentId                 identity of the chapter narration audio assignment
 * @param segmentId                    identity of the chapter narration segment
 * @param managedVoiceId               identity of the managed voice
 * @param mediaAssetId                 identity of the Media asset storing the audio
 * @param generatedSynthesisRevision   synthesis revision of the assigned audio
 * @param outcome                      outcome status (REUSED, GENERATED, STALE)
 */
public record GenerateChapterNarrationAudioResult(
        UUID assignmentId,
        UUID segmentId,
        UUID managedVoiceId,
        UUID mediaAssetId,
        long generatedSynthesisRevision,
        NarrationAudioGenerationOutcome outcome
) {
    public GenerateChapterNarrationAudioResult {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        Objects.requireNonNull(mediaAssetId, "mediaAssetId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
