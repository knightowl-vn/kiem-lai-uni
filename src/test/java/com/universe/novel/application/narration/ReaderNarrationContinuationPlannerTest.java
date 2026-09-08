package com.universe.novel.application.narration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReaderNarrationContinuationPlanner Unit Tests (MS-04.9H.7C2C1)")
class ReaderNarrationContinuationPlannerTest {

    private ReaderNarrationContinuationPlanner planner;
    private final UUID chapterId = UUID.randomUUID();
    private final UUID managedVoiceId = UUID.randomUUID();
    private final UUID requestedSegmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        planner = new ReaderNarrationContinuationPlanner();
    }

    private ReaderNarrationContinuationSegmentSnapshot createSnapshot(UUID id, int index, ChapterNarrationAudioHealthStatus health) {
        return new ReaderNarrationContinuationSegmentSnapshot(id, index, health);
    }

    @Nested
    @DisplayName("1-2. Requested Segment Exclusion Tests")
    class RequestedSegmentExclusionTests {

        @Test
        @DisplayName("1. Requested segment is excluded from continuation work items even when MISSING")
        void requestedSegmentExcludedWhenMissing() {
            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = List.of(
                    createSnapshot(requestedSegmentId, 0, ChapterNarrationAudioHealthStatus.MISSING)
            );

            ReaderNarrationContinuationPlan plan = planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots);

            assertThat(plan.workItems()).isEmpty();
            assertThat(plan.totalWorkCount()).isEqualTo(0);
            assertThat(plan.requestedSegmentIndex()).isEqualTo(0);
            assertThat(plan.hasWork()).isFalse();
        }

        @Test
        @DisplayName("2. Requested segment is excluded from continuation work items even when FAILED")
        void requestedSegmentExcludedWhenFailed() {
            UUID nextSeg = UUID.randomUUID();
            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = List.of(
                    createSnapshot(requestedSegmentId, 1, ChapterNarrationAudioHealthStatus.FAILED),
                    createSnapshot(nextSeg, 2, ChapterNarrationAudioHealthStatus.MISSING)
            );

            ReaderNarrationContinuationPlan plan = planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots);

            assertThat(plan.workItems()).hasSize(1);
            assertThat(plan.workItems().get(0).segmentId()).isEqualTo(nextSeg);
            assertThat(plan.requestedSegmentIndex()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("3. READY Omission Tests")
    class ReadyOmissionTests {

        @Test
        @DisplayName("3. READY segments are omitted from continuation plan")
        void readySegmentsOmitted() {
            UUID seg0 = UUID.randomUUID();
            UUID seg2 = UUID.randomUUID();
            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = List.of(
                    createSnapshot(seg0, 0, ChapterNarrationAudioHealthStatus.READY),
                    createSnapshot(requestedSegmentId, 1, ChapterNarrationAudioHealthStatus.READY),
                    createSnapshot(seg2, 2, ChapterNarrationAudioHealthStatus.READY)
            );

            ReaderNarrationContinuationPlan plan = planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots);

            assertThat(plan.workItems()).isEmpty();
            assertThat(plan.totalWorkCount()).isEqualTo(0);
            assertThat(plan.generateCount()).isEqualTo(0);
            assertThat(plan.regenerateCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("4-7. Priority Bucketing & Ordering Tests")
    class PriorityBucketingTests {

        @Test
        @DisplayName("4. Future MISSING/FAILED ordered ASC by segmentIndex")
        void futureMissingAndFailedOrderedAsc() {
            UUID seg2 = UUID.randomUUID();
            UUID seg3 = UUID.randomUUID();
            UUID seg4 = UUID.randomUUID();

            // Provided out of order in snapshot list
            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = List.of(
                    createSnapshot(seg4, 4, ChapterNarrationAudioHealthStatus.FAILED),
                    createSnapshot(requestedSegmentId, 1, ChapterNarrationAudioHealthStatus.READY),
                    createSnapshot(seg2, 2, ChapterNarrationAudioHealthStatus.MISSING),
                    createSnapshot(seg3, 3, ChapterNarrationAudioHealthStatus.FAILED)
            );

            ReaderNarrationContinuationPlan plan = planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots);

            assertThat(plan.workItems()).extracting(ReaderNarrationContinuationPlanItem::segmentIndex)
                    .containsExactly(2, 3, 4);
            assertThat(plan.workItems()).extracting(ReaderNarrationContinuationPlanItem::action)
                    .containsExactly(
                            ReaderNarrationContinuationAction.GENERATE,
                            ReaderNarrationContinuationAction.GENERATE,
                            ReaderNarrationContinuationAction.GENERATE
                    );
        }

        @Test
        @DisplayName("5. Future blocking work (MISSING/FAILED) comes before future refresh work (OUTDATED)")
        void futureBlockingBeforeFutureRefresh() {
            UUID seg2 = UUID.randomUUID();
            UUID seg3 = UUID.randomUUID();

            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = List.of(
                    createSnapshot(requestedSegmentId, 1, ChapterNarrationAudioHealthStatus.READY),
                    createSnapshot(seg2, 2, ChapterNarrationAudioHealthStatus.OUTDATED),
                    createSnapshot(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING)
            );

            ReaderNarrationContinuationPlan plan = planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots);

            // Even though seg2 (index 2) comes before seg3 (index 3), seg3 is blocking (MISSING) while seg2 is refresh (OUTDATED)
            assertThat(plan.workItems()).extracting(ReaderNarrationContinuationPlanItem::segmentIndex)
                    .containsExactly(3, 2);
            assertThat(plan.workItems()).extracting(ReaderNarrationContinuationPlanItem::action)
                    .containsExactly(
                            ReaderNarrationContinuationAction.GENERATE,
                            ReaderNarrationContinuationAction.REGENERATE
                    );
        }

        @Test
        @DisplayName("6. All future work comes before all past work")
        void allFutureWorkBeforeAllPastWork() {
            UUID seg0 = UUID.randomUUID();
            UUID seg3 = UUID.randomUUID();

            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = List.of(
                    createSnapshot(seg0, 0, ChapterNarrationAudioHealthStatus.MISSING),
                    createSnapshot(requestedSegmentId, 2, ChapterNarrationAudioHealthStatus.READY),
                    createSnapshot(seg3, 3, ChapterNarrationAudioHealthStatus.OUTDATED)
            );

            ReaderNarrationContinuationPlan plan = planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots);

            // Future refresh (index 3) must precede past blocking (index 0)
            assertThat(plan.workItems()).extracting(ReaderNarrationContinuationPlanItem::segmentIndex)
                    .containsExactly(3, 0);
            assertThat(plan.futureWorkCount()).isEqualTo(1);
            assertThat(plan.pastWorkCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("7. Past MISSING/FAILED comes before past OUTDATED")
        void pastBlockingBeforePastOutdated() {
            UUID seg0 = UUID.randomUUID();
            UUID seg1 = UUID.randomUUID();

            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = List.of(
                    createSnapshot(seg0, 0, ChapterNarrationAudioHealthStatus.OUTDATED),
                    createSnapshot(seg1, 1, ChapterNarrationAudioHealthStatus.FAILED),
                    createSnapshot(requestedSegmentId, 5, ChapterNarrationAudioHealthStatus.READY)
            );

            ReaderNarrationContinuationPlan plan = planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots);

            // Past blocking (seg1 at index 1) before past refresh (seg0 at index 0)
            assertThat(plan.workItems()).extracting(ReaderNarrationContinuationPlanItem::segmentIndex)
                    .containsExactly(1, 0);
            assertThat(plan.workItems()).extracting(ReaderNarrationContinuationPlanItem::action)
                    .containsExactly(
                            ReaderNarrationContinuationAction.GENERATE,
                            ReaderNarrationContinuationAction.REGENERATE
                    );
        }
    }

    @Nested
    @DisplayName("8-10. Action Mapping & Mixed Chapter End-to-End Tests")
    class ActionMappingAndMixedChapterTests {

        @Test
        @DisplayName("8. MISSING and FAILED map to GENERATE")
        void missingAndFailedMapToGenerate() {
            assertThat(planner.resolveAction(ChapterNarrationAudioHealthStatus.MISSING))
                    .contains(ReaderNarrationContinuationAction.GENERATE);
            assertThat(planner.resolveAction(ChapterNarrationAudioHealthStatus.FAILED))
                    .contains(ReaderNarrationContinuationAction.GENERATE);
        }

        @Test
        @DisplayName("9. OUTDATED maps to REGENERATE and READY maps to empty")
        void outdatedAndReadyActionMappings() {
            assertThat(planner.resolveAction(ChapterNarrationAudioHealthStatus.OUTDATED))
                    .contains(ReaderNarrationContinuationAction.REGENERATE);
            assertThat(planner.resolveAction(ChapterNarrationAudioHealthStatus.READY))
                    .isEmpty();
        }

        @Test
        @DisplayName("10. Mixed chapter produces exact deterministic expected 4-bucket order")
        void mixedChapterProducesExactDeterministicOrder() {
            // Requested segment index: 4
            // Past segments:
            //   0: OUTDATED (Bucket 4)
            //   1: MISSING  (Bucket 3)
            //   2: READY    (Omitted)
            //   3: FAILED   (Bucket 3)
            // Requested:
            //   4: MISSING  (Excluded)
            // Future segments:
            //   5: OUTDATED (Bucket 2)
            //   6: READY    (Omitted)
            //   7: MISSING  (Bucket 1)
            //   8: FAILED   (Bucket 1)
            //   9: OUTDATED (Bucket 2)

            UUID s0 = UUID.randomUUID();
            UUID s1 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();
            UUID s3 = UUID.randomUUID();
            UUID s4 = requestedSegmentId;
            UUID s5 = UUID.randomUUID();
            UUID s6 = UUID.randomUUID();
            UUID s7 = UUID.randomUUID();
            UUID s8 = UUID.randomUUID();
            UUID s9 = UUID.randomUUID();

            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = List.of(
                    createSnapshot(s9, 9, ChapterNarrationAudioHealthStatus.OUTDATED),
                    createSnapshot(s0, 0, ChapterNarrationAudioHealthStatus.OUTDATED),
                    createSnapshot(s4, 4, ChapterNarrationAudioHealthStatus.MISSING),
                    createSnapshot(s8, 8, ChapterNarrationAudioHealthStatus.FAILED),
                    createSnapshot(s2, 2, ChapterNarrationAudioHealthStatus.READY),
                    createSnapshot(s1, 1, ChapterNarrationAudioHealthStatus.MISSING),
                    createSnapshot(s7, 7, ChapterNarrationAudioHealthStatus.MISSING),
                    createSnapshot(s5, 5, ChapterNarrationAudioHealthStatus.OUTDATED),
                    createSnapshot(s3, 3, ChapterNarrationAudioHealthStatus.FAILED),
                    createSnapshot(s6, 6, ChapterNarrationAudioHealthStatus.READY)
            );

            ReaderNarrationContinuationPlan plan = planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots);

            // Bucket 1 (Future Blocking): indices 7, 8 (GENERATE)
            // Bucket 2 (Future Refresh): indices 5, 9 (REGENERATE)
            // Bucket 3 (Past Blocking): indices 1, 3 (GENERATE)
            // Bucket 4 (Past Refresh): index 0 (REGENERATE)
            // Total items = 7 (s2, s6 READY omitted; s4 requested excluded)

            assertThat(plan.requestedSegmentIndex()).isEqualTo(4);
            assertThat(plan.totalWorkCount()).isEqualTo(7);
            assertThat(plan.generateCount()).isEqualTo(4); // 7, 8, 1, 3
            assertThat(plan.regenerateCount()).isEqualTo(3); // 5, 9, 0
            assertThat(plan.futureWorkCount()).isEqualTo(4); // 7, 8, 5, 9
            assertThat(plan.pastWorkCount()).isEqualTo(3); // 1, 3, 0

            assertThat(plan.workItems()).extracting(ReaderNarrationContinuationPlanItem::segmentIndex)
                    .containsExactly(7, 8, 5, 9, 1, 3, 0);

            assertThat(plan.workItems()).extracting(ReaderNarrationContinuationPlanItem::action)
                    .containsExactly(
                            ReaderNarrationContinuationAction.GENERATE,
                            ReaderNarrationContinuationAction.GENERATE,
                            ReaderNarrationContinuationAction.REGENERATE,
                            ReaderNarrationContinuationAction.REGENERATE,
                            ReaderNarrationContinuationAction.GENERATE,
                            ReaderNarrationContinuationAction.GENERATE,
                            ReaderNarrationContinuationAction.REGENERATE
                    );
        }
    }

    @Nested
    @DisplayName("11-14. Validation & Error Handling Tests")
    class ValidationTests {

        @Test
        @DisplayName("11. Requested segment missing from snapshots -> rejected")
        void requestedSegmentMissingFromSnapshotsRejected() {
            UUID otherSeg = UUID.randomUUID();
            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = List.of(
                    createSnapshot(otherSeg, 0, ChapterNarrationAudioHealthStatus.MISSING)
            );

            assertThatThrownBy(() -> planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Requested segment");
        }

        @Test
        @DisplayName("12. Duplicate segmentId in snapshots -> rejected")
        void duplicateSegmentIdRejected() {
            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = List.of(
                    createSnapshot(requestedSegmentId, 0, ChapterNarrationAudioHealthStatus.READY),
                    createSnapshot(requestedSegmentId, 1, ChapterNarrationAudioHealthStatus.MISSING)
            );

            assertThatThrownBy(() -> planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate segmentId");
        }

        @Test
        @DisplayName("13. Duplicate segmentIndex in snapshots -> rejected")
        void duplicateSegmentIndexRejected() {
            UUID otherSeg = UUID.randomUUID();
            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = List.of(
                    createSnapshot(requestedSegmentId, 0, ChapterNarrationAudioHealthStatus.READY),
                    createSnapshot(otherSeg, 0, ChapterNarrationAudioHealthStatus.MISSING)
            );

            assertThatThrownBy(() -> planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate segmentIndex");
        }

        @Test
        @DisplayName("14. Null or invalid inputs rejected")
        void nullOrInvalidInputsRejected() {
            List<ReaderNarrationContinuationSegmentSnapshot> validSnapshots = List.of(
                    createSnapshot(requestedSegmentId, 0, ChapterNarrationAudioHealthStatus.READY)
            );

            assertThatThrownBy(() -> planner.plan(null, managedVoiceId, requestedSegmentId, validSnapshots))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> planner.plan(chapterId, null, requestedSegmentId, validSnapshots))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> planner.plan(chapterId, managedVoiceId, null, validSnapshots))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> planner.plan(chapterId, managedVoiceId, requestedSegmentId, null))
                    .isInstanceOf(NullPointerException.class);

            List<ReaderNarrationContinuationSegmentSnapshot> snapshotsWithNull = new ArrayList<>();
            snapshotsWithNull.add(null);
            assertThatThrownBy(() -> planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshotsWithNull))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new ReaderNarrationContinuationSegmentSnapshot(null, 0, ChapterNarrationAudioHealthStatus.READY))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ReaderNarrationContinuationSegmentSnapshot(UUID.randomUUID(), -1, ChapterNarrationAudioHealthStatus.READY))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ReaderNarrationContinuationSegmentSnapshot(UUID.randomUUID(), 0, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("15-16. Scalability & Determinism Tests")
    class ScalabilityAndDeterminismTests {

        @Test
        @DisplayName("15. Variable-size chapter has no fixed-cardinality assumption (e.g. 100+ segments)")
        void largeChapterHasNoFixedCardinalityAssumption() {
            int totalSegments = 120;
            int reqIndex = 50;
            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = new ArrayList<>(totalSegments);

            for (int i = 0; i < totalSegments; i++) {
                UUID id = (i == reqIndex) ? requestedSegmentId : UUID.randomUUID();
                ChapterNarrationAudioHealthStatus health = (i % 2 == 0)
                        ? ChapterNarrationAudioHealthStatus.MISSING
                        : ChapterNarrationAudioHealthStatus.OUTDATED;
                snapshots.add(createSnapshot(id, i, health));
            }

            ReaderNarrationContinuationPlan plan = planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots);

            // Requested index 50 is excluded; all other 119 segments are either MISSING or OUTDATED
            assertThat(plan.totalWorkCount()).isEqualTo(119);
            assertThat(plan.requestedSegmentIndex()).isEqualTo(50);
            assertThat(plan.futureWorkCount()).isEqualTo(69); // 51..119
            assertThat(plan.pastWorkCount()).isEqualTo(50); // 0..49
        }

        @Test
        @DisplayName("16. Repeated planning produces identical deterministic results")
        void repeatedPlanningProducesIdenticalResults() {
            UUID s0 = UUID.randomUUID();
            UUID s2 = UUID.randomUUID();
            UUID s3 = UUID.randomUUID();

            List<ReaderNarrationContinuationSegmentSnapshot> snapshots = List.of(
                    createSnapshot(s0, 0, ChapterNarrationAudioHealthStatus.OUTDATED),
                    createSnapshot(requestedSegmentId, 1, ChapterNarrationAudioHealthStatus.READY),
                    createSnapshot(s2, 2, ChapterNarrationAudioHealthStatus.MISSING),
                    createSnapshot(s3, 3, ChapterNarrationAudioHealthStatus.FAILED)
            );

            ReaderNarrationContinuationPlan plan1 = planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots);
            ReaderNarrationContinuationPlan plan2 = planner.plan(chapterId, managedVoiceId, requestedSegmentId, snapshots);

            assertThat(plan1.workItems()).isEqualTo(plan2.workItems());
            assertThat(plan1.totalWorkCount()).isEqualTo(plan2.totalWorkCount());
            assertThat(plan1.requestedSegmentIndex()).isEqualTo(plan2.requestedSegmentIndex());
        }
    }
}
