package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Result record returned by {@link HandoffRetiredNarrationAudioCleanupUseCase} (MS-04.9H.7C1C1).
 *
 * @param narrationAudioId identity of the narration audio assignment
 * @param segmentId        identity of the owning segment (null if assignment was already absent)
 * @param mediaAssetId     identity of the attached Media asset (null if assignment was already absent)
 * @param outcome          outcome of the handoff operation
 */
public record HandoffRetiredNarrationAudioCleanupResult(
        UUID narrationAudioId,
        UUID segmentId,
        UUID mediaAssetId,
        HandoffRetiredNarrationAudioCleanupOutcome outcome
) {
    public HandoffRetiredNarrationAudioCleanupResult {
        Objects.requireNonNull(narrationAudioId, "narrationAudioId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    public boolean isHandedOff() {
        return outcome.isHandedOff();
    }

    public boolean isAlreadyAbsent() {
        return outcome.isAlreadyAbsent();
    }

    public boolean isSkipped() {
        return outcome.isSkipped();
    }
}
