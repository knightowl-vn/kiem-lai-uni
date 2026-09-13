package com.universe.novel.application.narration;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure application planner that classifies CURRENT narration segments for a selected managed voice
 * and produces an immutable {@link ChapterNarrationGenerationPlan} (MS-04.9H.7C1A).
 * <p>
 * <strong>Classification Rules:</strong>
 * <ul>
 *     <li>{@link ChapterNarrationAudioHealthStatus#READY} &rarr; {@link ChapterNarrationGenerationAction#SKIP_READY}</li>
 *     <li>{@link ChapterNarrationAudioHealthStatus#MISSING} &rarr; {@link ChapterNarrationGenerationAction#GENERATE}</li>
 *     <li>{@link ChapterNarrationAudioHealthStatus#FAILED} &rarr; {@link ChapterNarrationGenerationAction#GENERATE}</li>
 *     <li>{@link ChapterNarrationAudioHealthStatus#OUTDATED} &rarr; {@link ChapterNarrationGenerationAction#REGENERATE}</li>
 * </ul>
 */
@Component
public class ChapterNarrationGenerationPlanner {

    private static final Comparator<ChapterNarrationSegmentHealthSnapshot> BY_SEGMENT_INDEX =
            Comparator.comparingInt(ChapterNarrationSegmentHealthSnapshot::segmentIndex);

    /**
     * Plans chapter narration generation for the specified chapter, managed voice, and segment health snapshots.
     *
     * @param chapterId        identity of the chapter (non-null)
     * @param managedVoiceId   identity of the managed voice (non-null)
     * @param segmentSnapshots collection of CURRENT segment health snapshots (non-null, no null elements)
     * @return immutable {@link ChapterNarrationGenerationPlan} sorted by segmentIndex ASC
     * @throws IllegalArgumentException if chapterId or managedVoiceId is null, segmentSnapshots is null or contains null,
     *                                  or duplicate segment IDs / indexes are detected
     */
    public ChapterNarrationGenerationPlan plan(
            UUID chapterId,
            UUID managedVoiceId,
            Collection<ChapterNarrationSegmentHealthSnapshot> segmentSnapshots
    ) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }
        if (managedVoiceId == null) {
            throw new IllegalArgumentException("managedVoiceId must not be null");
        }
        if (segmentSnapshots == null) {
            throw new IllegalArgumentException("segmentSnapshots must not be null");
        }

        if (segmentSnapshots.isEmpty()) {
            return new ChapterNarrationGenerationPlan(chapterId, managedVoiceId, Collections.emptyList());
        }

        Set<UUID> seenSegmentIds = new HashSet<>();
        Set<Integer> seenSegmentIndexes = new HashSet<>();

        List<ChapterNarrationSegmentHealthSnapshot> sortedSnapshots = new ArrayList<>(segmentSnapshots.size());

        for (ChapterNarrationSegmentHealthSnapshot snapshot : segmentSnapshots) {
            if (snapshot == null) {
                throw new IllegalArgumentException("segmentSnapshots must not contain null elements");
            }
            if (!seenSegmentIds.add(snapshot.segmentId())) {
                throw new IllegalArgumentException("Duplicate segment ID in generation plan input: " + snapshot.segmentId());
            }
            if (!seenSegmentIndexes.add(snapshot.segmentIndex())) {
                throw new IllegalArgumentException("Duplicate segment index in generation plan input: " + snapshot.segmentIndex());
            }
            sortedSnapshots.add(snapshot);
        }

        sortedSnapshots.sort(BY_SEGMENT_INDEX);

        List<ChapterNarrationGenerationPlanItem> items = new ArrayList<>(sortedSnapshots.size());
        for (ChapterNarrationSegmentHealthSnapshot snapshot : sortedSnapshots) {
            ChapterNarrationGenerationAction action = resolveAction(snapshot.healthStatus());
            items.add(new ChapterNarrationGenerationPlanItem(
                    snapshot.segmentId(),
                    snapshot.segmentIndex(),
                    snapshot.healthStatus(),
                    action
            ));
        }

        return new ChapterNarrationGenerationPlan(chapterId, managedVoiceId, items);
    }

    private ChapterNarrationGenerationAction resolveAction(ChapterNarrationAudioHealthStatus healthStatus) {
        if (healthStatus == null) {
            throw new IllegalArgumentException("healthStatus must not be null");
        }
        return switch (healthStatus) {
            case READY -> ChapterNarrationGenerationAction.SKIP_READY;
            case MISSING, FAILED -> ChapterNarrationGenerationAction.GENERATE;
            case OUTDATED -> ChapterNarrationGenerationAction.REGENERATE;
        };
    }
}
