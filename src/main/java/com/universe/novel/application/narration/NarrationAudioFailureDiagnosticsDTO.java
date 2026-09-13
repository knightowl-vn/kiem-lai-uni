package com.universe.novel.application.narration;

import com.universe.novel.domain.narration.NarrationAudioFailureStage;
import com.universe.novel.domain.narration.NarrationAudioOperation;

import java.time.Instant;
import java.util.Objects;

/**
 * Passive DTO carrying diagnostics of the latest unresolved narration audio generation/regeneration failure.
 *
 * @param operation                  operation type during which the failure occurred
 * @param stage                      failure execution stage
 * @param attemptedSynthesisRevision synthesis revision attempted during the failure
 * @param failureCount               cumulative count of consecutive failures
 * @param errorType                  sanitized error type / exception class name
 * @param errorMessage               sanitized, bounded error message
 * @param firstFailedAt              timestamp of the initial recorded failure
 * @param lastFailedAt               timestamp of the most recent failure occurrence
 */
public record NarrationAudioFailureDiagnosticsDTO(
        NarrationAudioOperation operation,
        NarrationAudioFailureStage stage,
        long attemptedSynthesisRevision,
        int failureCount,
        String errorType,
        String errorMessage,
        Instant firstFailedAt,
        Instant lastFailedAt
) {
    public NarrationAudioFailureDiagnosticsDTO {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(errorType, "errorType must not be null");
        Objects.requireNonNull(errorMessage, "errorMessage must not be null");
        Objects.requireNonNull(firstFailedAt, "firstFailedAt must not be null");
        Objects.requireNonNull(lastFailedAt, "lastFailedAt must not be null");
    }
}
