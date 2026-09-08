package com.universe.novel.application.narration;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable chapter-level narration generation plan for a selected managed voice (MS-04.9H.7C1A).
 *
 * @param chapterId      identity of the chapter
 * @param managedVoiceId identity of the selected managed voice
 * @param items          ordered immutable list of plan items sorted by segmentIndex ASC
 */
public record ChapterNarrationGenerationPlan(
        UUID chapterId,
        UUID managedVoiceId,
        List<ChapterNarrationGenerationPlanItem> items
) {
    public ChapterNarrationGenerationPlan {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        items = (items == null || items.isEmpty())
                ? Collections.emptyList()
                : List.copyOf(items);
    }

    /**
     * Total number of planned CURRENT segments.
     */
    public int totalSegments() {
        return items.size();
    }

    /**
     * Count of CURRENT segments whose audio is already READY and will be skipped.
     */
    public int readySkippedCount() {
        return (int) items.stream()
                .filter(ChapterNarrationGenerationPlanItem::isSkipReady)
                .count();
    }

    /**
     * Count of CURRENT segments requiring initial audio generation (MISSING or FAILED).
     */
    public int generationRequestedCount() {
        return (int) items.stream()
                .filter(ChapterNarrationGenerationPlanItem::isGenerate)
                .count();
    }

    /**
     * Count of CURRENT segments requiring audio regeneration (OUTDATED).
     */
    public int regenerationRequestedCount() {
        return (int) items.stream()
                .filter(ChapterNarrationGenerationPlanItem::isRegenerate)
                .count();
    }

    /**
     * Total count of segments requiring actual TTS synthesis / persistence work:
     * {@code generationRequestedCount + regenerationRequestedCount}.
     */
    public int workRequiredCount() {
        return generationRequestedCount() + regenerationRequestedCount();
    }

    /**
     * Returns true if at least one segment requires generation or regeneration.
     */
    public boolean hasWorkRequired() {
        return workRequiredCount() > 0;
    }

    /**
     * Returns true if there are zero planned segments.
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }
}
