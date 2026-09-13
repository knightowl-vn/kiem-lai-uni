package com.universe.novel.application.narration;

import java.util.Objects;

/**
 * Immutable summary of retired narration audio cleanup performed after chapter narration generation (MS-04.9H.7C1C2A).
 *
 * @param status                           coordinator execution status
 * @param currentNarrationReady            whether all CURRENT segments for the selected voice were READY after generation
 * @param retiredAudioCandidateCount       total number of RETIRED audio assignments identified for the selected voice
 * @param handedOffCount                   number of retired audio assignments successfully handed off to cleanup
 * @param alreadyAbsentCount               number of retired audio assignments already absent
 * @param skippedNotRetiredCount           number of audio assignments skipped because their segment was not RETIRED
 * @param skippedSharedMediaReferenceCount number of audio assignments skipped because their media asset is shared
 * @param failedCount                      number of retired audio assignments where handoff failed with an exception
 */
public record ChapterNarrationCompletionCleanupSummary(
        ChapterNarrationCompletionCleanupStatus status,
        boolean currentNarrationReady,
        int retiredAudioCandidateCount,
        int handedOffCount,
        int alreadyAbsentCount,
        int skippedNotRetiredCount,
        int skippedSharedMediaReferenceCount,
        int failedCount
) {
    public ChapterNarrationCompletionCleanupSummary {
        Objects.requireNonNull(status, "status must not be null");
        if (retiredAudioCandidateCount < 0) {
            throw new IllegalArgumentException("retiredAudioCandidateCount must not be negative");
        }
        if (handedOffCount < 0) {
            throw new IllegalArgumentException("handedOffCount must not be negative");
        }
        if (alreadyAbsentCount < 0) {
            throw new IllegalArgumentException("alreadyAbsentCount must not be negative");
        }
        if (skippedNotRetiredCount < 0) {
            throw new IllegalArgumentException("skippedNotRetiredCount must not be negative");
        }
        if (skippedSharedMediaReferenceCount < 0) {
            throw new IllegalArgumentException("skippedSharedMediaReferenceCount must not be negative");
        }
        if (failedCount < 0) {
            throw new IllegalArgumentException("failedCount must not be negative");
        }
        int attempted = handedOffCount + alreadyAbsentCount + skippedNotRetiredCount + skippedSharedMediaReferenceCount + failedCount;
        if (attempted > retiredAudioCandidateCount) {
            throw new IllegalArgumentException("cleanupAttemptedCount (" + attempted + ") cannot exceed retiredAudioCandidateCount (" + retiredAudioCandidateCount + ")");
        }
    }

    /**
     * Creates a summary for when chapter narration is not eligible for cleanup (e.g. not all CURRENT segments are READY, or manifest absent).
     */
    public static ChapterNarrationCompletionCleanupSummary notEligible() {
        return new ChapterNarrationCompletionCleanupSummary(
                ChapterNarrationCompletionCleanupStatus.NOT_ELIGIBLE,
                false, 0, 0, 0, 0, 0, 0
        );
    }

    /**
     * Creates a summary when CURRENT narration is READY but no retired audio assignments exist.
     */
    public static ChapterNarrationCompletionCleanupSummary clean(boolean currentNarrationReady) {
        return new ChapterNarrationCompletionCleanupSummary(
                currentNarrationReady ? ChapterNarrationCompletionCleanupStatus.COMPLETED : ChapterNarrationCompletionCleanupStatus.NOT_ELIGIBLE,
                currentNarrationReady, 0, 0, 0, 0, 0, 0
        );
    }

    /**
     * Creates a summary when chapter narration manifest changed or disappeared prior to handoff execution.
     */
    public static ChapterNarrationCompletionCleanupSummary manifestChanged(int candidateCount) {
        return new ChapterNarrationCompletionCleanupSummary(
                ChapterNarrationCompletionCleanupStatus.MANIFEST_CHANGED,
                true, candidateCount, 0, 0, 0, 0, 0
        );
    }

    /**
     * Creates a safe fallback summary when the cleanup coordinator unexpectedly throws a top-level exception.
     */
    public static ChapterNarrationCompletionCleanupSummary coordinatorFailed() {
        return new ChapterNarrationCompletionCleanupSummary(
                ChapterNarrationCompletionCleanupStatus.COORDINATOR_FAILED,
                false, 0, 0, 0, 0, 0, 0
        );
    }

    /**
     * Total number of candidates where cleanup handoff was attempted.
     */
    public int cleanupAttemptedCount() {
        return handedOffCount + alreadyAbsentCount + skippedNotRetiredCount + skippedSharedMediaReferenceCount + failedCount;
    }

    /**
     * Total number of candidates that were not successfully handed off or absent.
     */
    public int remainingCleanupCount() {
        return Math.max(0, retiredAudioCandidateCount - (handedOffCount + alreadyAbsentCount));
    }

    /**
     * Returns true if CURRENT narration is READY and all candidate retired audio assignments have been cleaned up.
     */
    public boolean cleanupComplete() {
        return status == ChapterNarrationCompletionCleanupStatus.COMPLETED && remainingCleanupCount() == 0;
    }
}
