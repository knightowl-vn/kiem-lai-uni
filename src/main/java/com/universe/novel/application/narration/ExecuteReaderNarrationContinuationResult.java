package com.universe.novel.application.narration;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable execution result for an entire reader narration continuation plan (MS-04.9H.7C2C2).
 *
 * @param chapterId             identity of the chapter (non-null)
 * @param managedVoiceId        identity of the managed voice (non-null)
 * @param requestedSegmentId    identity of the requested segment (non-null)
 * @param totalPlannedCount     total number of work items originally in the plan (>= 0)
 * @param itemResults           immutable list of results for processed work items (non-null)
 * @param status                execution status of the plan run (non-null)
 */
public record ExecuteReaderNarrationContinuationResult(
        UUID chapterId,
        UUID managedVoiceId,
        UUID requestedSegmentId,
        int totalPlannedCount,
        List<ReaderNarrationContinuationItemResult> itemResults,
        ReaderNarrationContinuationExecutionStatus status
) {
    public ExecuteReaderNarrationContinuationResult {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        Objects.requireNonNull(requestedSegmentId, "requestedSegmentId must not be null");
        if (totalPlannedCount < 0) {
            throw new IllegalArgumentException("totalPlannedCount must not be negative: " + totalPlannedCount);
        }
        Objects.requireNonNull(itemResults, "itemResults must not be null");
        Objects.requireNonNull(status, "status must not be null");
        itemResults = List.copyOf(itemResults);
    }

    /**
     * Number of work items for which external synthesis/upload primitive was attempted.
     */
    public int attemptedCount() {
        return (int) itemResults.stream()
                .filter(ReaderNarrationContinuationItemResult::wasAttempted)
                .count();
    }

    /**
     * Number of work items skipped because audio was already fresh and READY.
     */
    public int skippedCount() {
        return (int) itemResults.stream()
                .filter(ReaderNarrationContinuationItemResult::isSkipped)
                .count();
    }

    /**
     * Number of work items successfully completed into ready/outdated or skipped state.
     */
    public int completedCount() {
        return (int) itemResults.stream()
                .filter(ReaderNarrationContinuationItemResult::isSuccess)
                .count();
    }

    /**
     * Number of work items that failed, require retry, or encountered stale context.
     */
    public int failedCount() {
        return (int) itemResults.stream()
                .filter(ReaderNarrationContinuationItemResult::isFailed)
                .count();
    }

    /**
     * Number of planned work items that were not executed due to abort or early termination.
     */
    public int remainingCount() {
        return Math.max(0, totalPlannedCount - itemResults.size());
    }

    /**
     * Returns true if all planned items were executed without abort, zero items remain, and zero items failed.
     */
    public boolean isFullyCompleted() {
        return status == ReaderNarrationContinuationExecutionStatus.COMPLETED
                && remainingCount() == 0
                && failedCount() == 0;
    }
}
