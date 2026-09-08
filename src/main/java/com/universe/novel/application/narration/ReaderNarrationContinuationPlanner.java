package com.universe.novel.application.narration;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, stateless planner that creates a prioritized reader narration continuation plan (MS-04.9H.7C2C1).
 * <p>
 * <strong>Prioritization Rules:</strong>
 * <ol>
 *     <li><strong>Excluded:</strong> The {@code requestedSegmentId} is excluded (already handled for immediate playback).</li>
 *     <li><strong>Omitted:</strong> Segments with {@link ChapterNarrationAudioHealthStatus#READY} health are omitted.</li>
 *     <li><strong>Bucket 1 (Future Blocking):</strong> {@code segmentIndex > requestedSegmentIndex} with {@code MISSING} or {@code FAILED} health (action: {@code GENERATE}, sorted ASC).</li>
 *     <li><strong>Bucket 2 (Future Refresh):</strong> {@code segmentIndex > requestedSegmentIndex} with {@code OUTDATED} health (action: {@code REGENERATE}, sorted ASC).</li>
 *     <li><strong>Bucket 3 (Past Blocking):</strong> {@code segmentIndex < requestedSegmentIndex} with {@code MISSING} or {@code FAILED} health (action: {@code GENERATE}, sorted ASC).</li>
 *     <li><strong>Bucket 4 (Past Refresh):</strong> {@code segmentIndex < requestedSegmentIndex} with {@code OUTDATED} health (action: {@code REGENERATE}, sorted ASC).</li>
 * </ol>
 */
@Component
public class ReaderNarrationContinuationPlanner {

    /**
     * Constructs a prioritized continuation plan from CURRENT segment snapshots.
     *
     * @param chapterId          identity of the chapter (non-null)
     * @param managedVoiceId     identity of the managed voice (non-null)
     * @param requestedSegmentId identity of the segment requested by reader for immediate playback (non-null)
     * @param snapshots          collection of CURRENT segment health snapshots (non-null, no null elements)
     * @return immutable prioritized continuation plan
     */
    public ReaderNarrationContinuationPlan plan(
            UUID chapterId,
            UUID managedVoiceId,
            UUID requestedSegmentId,
            Collection<ReaderNarrationContinuationSegmentSnapshot> snapshots
    ) {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        Objects.requireNonNull(requestedSegmentId, "requestedSegmentId must not be null");
        Objects.requireNonNull(snapshots, "snapshots must not be null");

        Set<UUID> seenSegmentIds = new HashSet<>();
        Set<Integer> seenSegmentIndices = new HashSet<>();
        ReaderNarrationContinuationSegmentSnapshot requestedSnapshot = null;

        for (ReaderNarrationContinuationSegmentSnapshot snapshot : snapshots) {
            if (snapshot == null) {
                throw new IllegalArgumentException("Snapshot element must not be null");
            }
            if (!seenSegmentIds.add(snapshot.segmentId())) {
                throw new IllegalArgumentException("Duplicate segmentId found in snapshots: " + snapshot.segmentId());
            }
            if (!seenSegmentIndices.add(snapshot.segmentIndex())) {
                throw new IllegalArgumentException("Duplicate segmentIndex found in snapshots: " + snapshot.segmentIndex());
            }
            if (snapshot.segmentId().equals(requestedSegmentId)) {
                requestedSnapshot = snapshot;
            }
        }

        if (requestedSnapshot == null) {
            throw new IllegalArgumentException("Requested segment [" + requestedSegmentId + "] was not found in snapshots");
        }

        int requestedSegmentIndex = requestedSnapshot.segmentIndex();

        List<ReaderNarrationContinuationPlanItem> futureBlocking = new ArrayList<>();
        List<ReaderNarrationContinuationPlanItem> futureRefresh = new ArrayList<>();
        List<ReaderNarrationContinuationPlanItem> pastBlocking = new ArrayList<>();
        List<ReaderNarrationContinuationPlanItem> pastRefresh = new ArrayList<>();

        for (ReaderNarrationContinuationSegmentSnapshot snapshot : snapshots) {
            // Exclude the requested segment
            if (snapshot.segmentId().equals(requestedSegmentId)) {
                continue;
            }

            Optional<ReaderNarrationContinuationAction> actionOpt = resolveAction(snapshot.health());
            if (actionOpt.isEmpty()) {
                // READY health is omitted from continuation plan
                continue;
            }

            ReaderNarrationContinuationAction action = actionOpt.get();
            ReaderNarrationContinuationPlanItem item = new ReaderNarrationContinuationPlanItem(
                    snapshot.segmentId(),
                    snapshot.segmentIndex(),
                    snapshot.health(),
                    action
            );

            if (snapshot.segmentIndex() > requestedSegmentIndex) {
                if (action == ReaderNarrationContinuationAction.GENERATE) {
                    futureBlocking.add(item);
                } else {
                    futureRefresh.add(item);
                }
            } else {
                if (action == ReaderNarrationContinuationAction.GENERATE) {
                    pastBlocking.add(item);
                } else {
                    pastRefresh.add(item);
                }
            }
        }

        // Sort each bucket by segmentIndex ASC
        futureBlocking.sort(Comparator.comparingInt(ReaderNarrationContinuationPlanItem::segmentIndex));
        futureRefresh.sort(Comparator.comparingInt(ReaderNarrationContinuationPlanItem::segmentIndex));
        pastBlocking.sort(Comparator.comparingInt(ReaderNarrationContinuationPlanItem::segmentIndex));
        pastRefresh.sort(Comparator.comparingInt(ReaderNarrationContinuationPlanItem::segmentIndex));

        List<ReaderNarrationContinuationPlanItem> orderedWorkItems = new ArrayList<>(
                futureBlocking.size() + futureRefresh.size() + pastBlocking.size() + pastRefresh.size()
        );
        orderedWorkItems.addAll(futureBlocking);
        orderedWorkItems.addAll(futureRefresh);
        orderedWorkItems.addAll(pastBlocking);
        orderedWorkItems.addAll(pastRefresh);

        return new ReaderNarrationContinuationPlan(
                chapterId,
                managedVoiceId,
                requestedSegmentId,
                requestedSegmentIndex,
                orderedWorkItems
        );
    }

    /**
     * Resolves the continuation action for a given health status.
     *
     * @param health audio health status (non-null)
     * @return optional action, empty for {@link ChapterNarrationAudioHealthStatus#READY}
     */
    public Optional<ReaderNarrationContinuationAction> resolveAction(ChapterNarrationAudioHealthStatus health) {
        Objects.requireNonNull(health, "health must not be null");
        return switch (health) {
            case MISSING, FAILED -> Optional.of(ReaderNarrationContinuationAction.GENERATE);
            case OUTDATED -> Optional.of(ReaderNarrationContinuationAction.REGENERATE);
            case READY -> Optional.empty();
        };
    }
}
