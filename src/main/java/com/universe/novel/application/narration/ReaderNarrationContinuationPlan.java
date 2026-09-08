package com.universe.novel.application.narration;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable prioritized continuation plan for background reader narration preparation (MS-04.9H.7C2C1).
 *
 * @param chapterId             identity of the chapter (non-null)
 * @param managedVoiceId        identity of the managed voice (non-null)
 * @param requestedSegmentId    identity of the segment requested by reader for immediate playback (non-null, excluded from plan)
 * @param requestedSegmentIndex zero-based index of the requested segment (>= 0)
 * @param workItems             ordered list of continuation work items (non-null, defensive copy)
 */
public record ReaderNarrationContinuationPlan(
        UUID chapterId,
        UUID managedVoiceId,
        UUID requestedSegmentId,
        int requestedSegmentIndex,
        List<ReaderNarrationContinuationPlanItem> workItems
) {
    public ReaderNarrationContinuationPlan {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        Objects.requireNonNull(requestedSegmentId, "requestedSegmentId must not be null");
        if (requestedSegmentIndex < 0) {
            throw new IllegalArgumentException("requestedSegmentIndex must not be negative: " + requestedSegmentIndex);
        }
        Objects.requireNonNull(workItems, "workItems must not be null");
        workItems = List.copyOf(workItems);
    }

    /**
     * Total number of continuation work items scheduled in this plan.
     */
    public int totalWorkCount() {
        return workItems.size();
    }

    /**
     * Number of items requiring new audio generation (MISSING / FAILED).
     */
    public int generateCount() {
        return (int) workItems.stream()
                .filter(item -> item.action() == ReaderNarrationContinuationAction.GENERATE)
                .count();
    }

    /**
     * Number of items requiring audio regeneration (OUTDATED).
     */
    public int regenerateCount() {
        return (int) workItems.stream()
                .filter(item -> item.action() == ReaderNarrationContinuationAction.REGENERATE)
                .count();
    }

    /**
     * Number of work items located after the requested segment (segmentIndex > requestedSegmentIndex).
     */
    public int futureWorkCount() {
        return (int) workItems.stream()
                .filter(item -> item.segmentIndex() > requestedSegmentIndex)
                .count();
    }

    /**
     * Number of work items located before the requested segment (segmentIndex < requestedSegmentIndex).
     */
    public int pastWorkCount() {
        return (int) workItems.stream()
                .filter(item -> item.segmentIndex() < requestedSegmentIndex)
                .count();
    }

    /**
     * Returns true if there are any continuation work items in this plan.
     */
    public boolean hasWork() {
        return !workItems.isEmpty();
    }

    /**
     * Returns true if this plan contains no continuation work items.
     */
    public boolean isEmpty() {
        return workItems.isEmpty();
    }
}
