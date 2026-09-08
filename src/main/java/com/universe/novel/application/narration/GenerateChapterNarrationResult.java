package com.universe.novel.application.narration;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable result returned after executing chapter-level narration generation (MS-04.9H.7C1B).
 *
 * @param chapterId      identity of the chapter
 * @param managedVoiceId identity of the managed voice used
 * @param items          ordered list of segment execution results sorted by segmentIndex ASC
 * @param cleanupSummary summary of completion cleanup performed for retired audio (MS-04.9H.7C1C2)
 */
public record GenerateChapterNarrationResult(
        UUID chapterId,
        UUID managedVoiceId,
        List<ChapterNarrationSegmentExecutionResult> items,
        ChapterNarrationCompletionCleanupSummary cleanupSummary
) {
    public GenerateChapterNarrationResult(
            UUID chapterId,
            UUID managedVoiceId,
            List<ChapterNarrationSegmentExecutionResult> items
    ) {
        this(chapterId, managedVoiceId, items, ChapterNarrationCompletionCleanupSummary.notEligible());
    }

    public GenerateChapterNarrationResult {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        items = (items == null || items.isEmpty())
                ? Collections.emptyList()
                : List.copyOf(items);
        if (cleanupSummary == null) {
            cleanupSummary = ChapterNarrationCompletionCleanupSummary.notEligible();
        }
    }

    /**
     * Total number of planned CURRENT segments in the chapter.
     */
    public int totalSegments() {
        return items.size();
    }

    /**
     * Count of segments whose audio was already READY and skipped.
     */
    public int skippedReadyCount() {
        return (int) items.stream()
                .filter(ChapterNarrationSegmentExecutionResult::isSkipped)
                .count();
    }

    /**
     * Count of segments where initial audio generation succeeded (GENERATED or REUSED).
     */
    public int generatedCount() {
        return (int) items.stream()
                .filter(i -> i.executionOutcome() == ChapterNarrationGenerationExecutionOutcome.GENERATED
                        || i.executionOutcome() == ChapterNarrationGenerationExecutionOutcome.REUSED)
                .count();
    }

    /**
     * Count of segments where audio regeneration succeeded (REGENERATED or ALREADY_CURRENT).
     */
    public int regeneratedCount() {
        return (int) items.stream()
                .filter(i -> i.executionOutcome() == ChapterNarrationGenerationExecutionOutcome.REGENERATED
                        || i.executionOutcome() == ChapterNarrationGenerationExecutionOutcome.ALREADY_CURRENT)
                .count();
    }

    /**
     * Total count of segments where generation or regeneration work completed successfully.
     */
    public int completedWorkCount() {
        return (int) items.stream()
                .filter(ChapterNarrationSegmentExecutionResult::isCompletedWork)
                .count();
    }

    /**
     * Count of segments where generation or regeneration threw an exception and failed.
     */
    public int failedCount() {
        return (int) items.stream()
                .filter(ChapterNarrationSegmentExecutionResult::isFailed)
                .count();
    }

    /**
     * Count of segments where stale assignment was encountered during generation requiring regeneration.
     */
    public int retryRequiredCount() {
        return (int) items.stream()
                .filter(ChapterNarrationSegmentExecutionResult::isRetryRequired)
                .count();
    }

    /**
     * Count of segments that still need work (failed + retry required).
     */
    public int remainingWorkCount() {
        return failedCount() + retryRequiredCount();
    }

    /**
     * Returns true if all planned segments are in a successful/skipped state with zero failures or retries.
     */
    public boolean isCompleteSuccess() {
        return remainingWorkCount() == 0;
    }

    /**
     * Returns true if there were zero planned CURRENT segments.
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }
}
