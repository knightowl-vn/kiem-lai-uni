package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Execution result for an individual CURRENT narration segment in a chapter narration generation run (MS-04.9H.7C1B).
 *
 * @param segmentId        unique identity of the narration segment
 * @param segmentIndex     0-based position of the segment within the chapter
 * @param plannedAction    the action determined during planning
 * @param executionOutcome the outcome resulting from execution
 * @param errorType        error type class name if failed, or null
 * @param errorMessage     sanitized error message if failed, or null
 */
public record ChapterNarrationSegmentExecutionResult(
        UUID segmentId,
        int segmentIndex,
        ChapterNarrationGenerationAction plannedAction,
        ChapterNarrationGenerationExecutionOutcome executionOutcome,
        String errorType,
        String errorMessage
) {
    public ChapterNarrationSegmentExecutionResult {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be non-negative: " + segmentIndex);
        }
        Objects.requireNonNull(plannedAction, "plannedAction must not be null");
        Objects.requireNonNull(executionOutcome, "executionOutcome must not be null");
    }

    public static ChapterNarrationSegmentExecutionResult skippedReady(UUID segmentId, int segmentIndex) {
        return new ChapterNarrationSegmentExecutionResult(
                segmentId,
                segmentIndex,
                ChapterNarrationGenerationAction.SKIP_READY,
                ChapterNarrationGenerationExecutionOutcome.SKIPPED_READY,
                null,
                null
        );
    }

    public static ChapterNarrationSegmentExecutionResult success(
            UUID segmentId,
            int segmentIndex,
            ChapterNarrationGenerationAction plannedAction,
            ChapterNarrationGenerationExecutionOutcome executionOutcome
    ) {
        return new ChapterNarrationSegmentExecutionResult(
                segmentId,
                segmentIndex,
                plannedAction,
                executionOutcome,
                null,
                null
        );
    }

    public static ChapterNarrationSegmentExecutionResult failure(
            UUID segmentId,
            int segmentIndex,
            ChapterNarrationGenerationAction plannedAction,
            String errorType,
            String errorMessage
    ) {
        return new ChapterNarrationSegmentExecutionResult(
                segmentId,
                segmentIndex,
                plannedAction,
                ChapterNarrationGenerationExecutionOutcome.FAILED,
                errorType,
                errorMessage
        );
    }

    public boolean isSuccess() {
        return executionOutcome.isSuccess();
    }

    public boolean isCompletedWork() {
        return executionOutcome.isCompletedWork();
    }

    public boolean isSkipped() {
        return executionOutcome == ChapterNarrationGenerationExecutionOutcome.SKIPPED_READY;
    }

    public boolean isFailed() {
        return executionOutcome == ChapterNarrationGenerationExecutionOutcome.FAILED;
    }

    public boolean isRetryRequired() {
        return executionOutcome == ChapterNarrationGenerationExecutionOutcome.RETRY_REQUIRED;
    }
}
