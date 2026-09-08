package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable execution result for an individual narration continuation work item (MS-04.9H.7C2C2).
 *
 * @param segmentId                  identity of the narration segment (non-null)
 * @param segmentIndex               zero-based index of the segment (>= 0)
 * @param plannedAction              action planned during initial C1 plan creation (non-null)
 * @param freshHealthBeforeExecution audio health derived right before execution (nullable if context failed before check)
 * @param executedAction             action actually dispatched to primitive (null if skipped)
 * @param finalHealth                authoritative health derived after execution (nullable if context became unavailable)
 * @param outcome                    outcome status of this item (non-null)
 */
public record ReaderNarrationContinuationItemResult(
        UUID segmentId,
        int segmentIndex,
        ReaderNarrationContinuationAction plannedAction,
        ChapterNarrationAudioHealthStatus freshHealthBeforeExecution,
        ReaderNarrationContinuationAction executedAction,
        ChapterNarrationAudioHealthStatus finalHealth,
        ReaderNarrationContinuationItemOutcome outcome
) {
    public ReaderNarrationContinuationItemResult {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must not be negative: " + segmentIndex);
        }
        Objects.requireNonNull(plannedAction, "plannedAction must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    /**
     * Returns true if external generation/regeneration primitive was dispatched for this item.
     */
    public boolean wasAttempted() {
        return executedAction != null;
    }

    /**
     * Returns true if this item was skipped because fresh health was already READY.
     */
    public boolean isSkipped() {
        return outcome == ReaderNarrationContinuationItemOutcome.SKIPPED_ALREADY_READY;
    }

    /**
     * Returns true if this item completed into a ready or outdated audio state.
     */
    public boolean isSuccess() {
        return outcome == ReaderNarrationContinuationItemOutcome.COMPLETED_READY
                || outcome == ReaderNarrationContinuationItemOutcome.COMPLETED_OUTDATED
                || outcome == ReaderNarrationContinuationItemOutcome.SKIPPED_ALREADY_READY;
    }

    /**
     * Returns true if this item failed or encountered a stale context.
     */
    public boolean isFailed() {
        return outcome == ReaderNarrationContinuationItemOutcome.FAILED
                || outcome == ReaderNarrationContinuationItemOutcome.RETRY_REQUIRED
                || outcome == ReaderNarrationContinuationItemOutcome.STALE_CONTEXT;
    }
}
