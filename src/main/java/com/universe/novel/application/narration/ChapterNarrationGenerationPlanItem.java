package com.universe.novel.application.narration;

import java.util.Objects;
import java.util.UUID;

/**
 * Planned generation action item for a specific CURRENT narration segment (MS-04.9H.7C1A).
 *
 * @param segmentId    unique identity of the narration segment
 * @param segmentIndex 0-based position of the segment within the chapter
 * @param healthStatus input derived health status
 * @param action       resolved generation action (SKIP_READY, GENERATE, REGENERATE)
 */
public record ChapterNarrationGenerationPlanItem(
        UUID segmentId,
        int segmentIndex,
        ChapterNarrationAudioHealthStatus healthStatus,
        ChapterNarrationGenerationAction action
) {
    public ChapterNarrationGenerationPlanItem {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be non-negative: " + segmentIndex);
        }
        Objects.requireNonNull(healthStatus, "healthStatus must not be null");
        Objects.requireNonNull(action, "action must not be null");
    }

    public boolean isSkipReady() {
        return action == ChapterNarrationGenerationAction.SKIP_READY;
    }

    public boolean isGenerate() {
        return action == ChapterNarrationGenerationAction.GENERATE;
    }

    public boolean isRegenerate() {
        return action == ChapterNarrationGenerationAction.REGENERATE;
    }

    public boolean isWorkRequired() {
        return action != ChapterNarrationGenerationAction.SKIP_READY;
    }
}
