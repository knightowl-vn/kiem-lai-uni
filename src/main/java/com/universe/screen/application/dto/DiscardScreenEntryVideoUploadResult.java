package com.universe.screen.application.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Result representing the outcome of a guarded video discard operation.
 */
public record DiscardScreenEntryVideoUploadResult(
        UUID videoMediaAssetId,
        DiscardOutcome outcome,
        String message
) {
    public DiscardScreenEntryVideoUploadResult {
        Objects.requireNonNull(videoMediaAssetId, "videoMediaAssetId cannot be null.");
        Objects.requireNonNull(outcome, "outcome cannot be null.");
        Objects.requireNonNull(message, "message cannot be null.");
    }

    public enum DiscardOutcome {
        DISCARDED,
        ALREADY_DISCARDED
    }
}
