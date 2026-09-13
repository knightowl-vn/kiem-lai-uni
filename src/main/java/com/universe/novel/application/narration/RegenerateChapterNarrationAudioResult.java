package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Result record returned after processing a narration audio regeneration request.
 *
 * @param assignmentId               identity of the chapter narration audio assignment
 * @param segmentId                  identity of the chapter narration segment
 * @param managedVoiceId             identity of the managed voice
 * @param previousMediaAssetId       identity of the Media asset that was attached prior to regeneration
 * @param currentMediaAssetId        identity of the Media asset currently attached after execution
 * @param generatedSynthesisRevision synthesis revision of the current audio assignment
 * @param outcome                    outcome status (ALREADY_CURRENT or REGENERATED)
 */
public record RegenerateChapterNarrationAudioResult(
        UUID assignmentId,
        UUID segmentId,
        UUID managedVoiceId,
        UUID previousMediaAssetId,
        UUID currentMediaAssetId,
        long generatedSynthesisRevision,
        RegenerateNarrationAudioOutcome outcome
) {
    public RegenerateChapterNarrationAudioResult {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        Objects.requireNonNull(previousMediaAssetId, "previousMediaAssetId must not be null");
        Objects.requireNonNull(currentMediaAssetId, "currentMediaAssetId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
