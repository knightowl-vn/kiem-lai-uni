package com.universe.novel.application.narration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChapterNarrationGenerationPlanner Unit Tests")
class ChapterNarrationGenerationPlannerTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID SEGMENT_1 = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID SEGMENT_2 = UUID.fromString("33333333-3333-3333-3333-333333333332");
    private static final UUID SEGMENT_3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SEGMENT_4 = UUID.fromString("33333333-3333-3333-3333-333333333334");

    private ChapterNarrationGenerationPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new ChapterNarrationGenerationPlanner();
    }

    @Test
    @DisplayName("1. READY health status maps to SKIP_READY action")
    void shouldMapReadyToSkipReady() {
        ChapterNarrationSegmentHealthSnapshot snapshot =
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, 0, ChapterNarrationAudioHealthStatus.READY);

        ChapterNarrationGenerationPlan plan = planner.plan(CHAPTER_ID, VOICE_ID, List.of(snapshot));

        assertThat(plan.totalSegments()).isEqualTo(1);
        assertThat(plan.items()).hasSize(1);
        ChapterNarrationGenerationPlanItem item = plan.items().get(0);
        assertThat(item.segmentId()).isEqualTo(SEGMENT_1);
        assertThat(item.segmentIndex()).isEqualTo(0);
        assertThat(item.healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
        assertThat(item.action()).isEqualTo(ChapterNarrationGenerationAction.SKIP_READY);
        assertThat(item.isSkipReady()).isTrue();
        assertThat(item.isGenerate()).isFalse();
        assertThat(item.isRegenerate()).isFalse();
        assertThat(item.isWorkRequired()).isFalse();

        assertThat(plan.readySkippedCount()).isEqualTo(1);
        assertThat(plan.generationRequestedCount()).isEqualTo(0);
        assertThat(plan.regenerationRequestedCount()).isEqualTo(0);
        assertThat(plan.workRequiredCount()).isEqualTo(0);
        assertThat(plan.hasWorkRequired()).isFalse();
        assertThat(plan.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("2. MISSING health status maps to GENERATE action")
    void shouldMapMissingToGenerate() {
        ChapterNarrationSegmentHealthSnapshot snapshot =
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, 0, ChapterNarrationAudioHealthStatus.MISSING);

        ChapterNarrationGenerationPlan plan = planner.plan(CHAPTER_ID, VOICE_ID, List.of(snapshot));

        assertThat(plan.totalSegments()).isEqualTo(1);
        ChapterNarrationGenerationPlanItem item = plan.items().get(0);
        assertThat(item.healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.MISSING);
        assertThat(item.action()).isEqualTo(ChapterNarrationGenerationAction.GENERATE);
        assertThat(item.isGenerate()).isTrue();
        assertThat(item.isSkipReady()).isFalse();
        assertThat(item.isWorkRequired()).isTrue();

        assertThat(plan.readySkippedCount()).isEqualTo(0);
        assertThat(plan.generationRequestedCount()).isEqualTo(1);
        assertThat(plan.regenerationRequestedCount()).isEqualTo(0);
        assertThat(plan.workRequiredCount()).isEqualTo(1);
        assertThat(plan.hasWorkRequired()).isTrue();
    }

    @Test
    @DisplayName("3. FAILED health status maps to GENERATE action")
    void shouldMapFailedToGenerate() {
        ChapterNarrationSegmentHealthSnapshot snapshot =
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, 0, ChapterNarrationAudioHealthStatus.FAILED);

        ChapterNarrationGenerationPlan plan = planner.plan(CHAPTER_ID, VOICE_ID, List.of(snapshot));

        assertThat(plan.totalSegments()).isEqualTo(1);
        ChapterNarrationGenerationPlanItem item = plan.items().get(0);
        assertThat(item.healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.FAILED);
        assertThat(item.action()).isEqualTo(ChapterNarrationGenerationAction.GENERATE);
        assertThat(item.isGenerate()).isTrue();
        assertThat(item.isWorkRequired()).isTrue();

        assertThat(plan.readySkippedCount()).isEqualTo(0);
        assertThat(plan.generationRequestedCount()).isEqualTo(1);
        assertThat(plan.regenerationRequestedCount()).isEqualTo(0);
        assertThat(plan.workRequiredCount()).isEqualTo(1);
        assertThat(plan.hasWorkRequired()).isTrue();
    }

    @Test
    @DisplayName("4. OUTDATED health status maps to REGENERATE action")
    void shouldMapOutdatedToRegenerate() {
        ChapterNarrationSegmentHealthSnapshot snapshot =
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, 0, ChapterNarrationAudioHealthStatus.OUTDATED);

        ChapterNarrationGenerationPlan plan = planner.plan(CHAPTER_ID, VOICE_ID, List.of(snapshot));

        assertThat(plan.totalSegments()).isEqualTo(1);
        ChapterNarrationGenerationPlanItem item = plan.items().get(0);
        assertThat(item.healthStatus()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
        assertThat(item.action()).isEqualTo(ChapterNarrationGenerationAction.REGENERATE);
        assertThat(item.isRegenerate()).isTrue();
        assertThat(item.isWorkRequired()).isTrue();

        assertThat(plan.readySkippedCount()).isEqualTo(0);
        assertThat(plan.generationRequestedCount()).isEqualTo(0);
        assertThat(plan.regenerationRequestedCount()).isEqualTo(1);
        assertThat(plan.workRequiredCount()).isEqualTo(1);
        assertThat(plan.hasWorkRequired()).isTrue();
    }

    @Test
    @DisplayName("5. Mixed health list produces exact actions and derived counts")
    void shouldPlanMixedHealthListWithExactDerivedCounts() {
        List<ChapterNarrationSegmentHealthSnapshot> snapshots = List.of(
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, 0, ChapterNarrationAudioHealthStatus.READY),
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_2, 1, ChapterNarrationAudioHealthStatus.OUTDATED),
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_3, 2, ChapterNarrationAudioHealthStatus.MISSING),
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_4, 3, ChapterNarrationAudioHealthStatus.FAILED)
        );

        ChapterNarrationGenerationPlan plan = planner.plan(CHAPTER_ID, VOICE_ID, snapshots);

        assertThat(plan.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(plan.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(plan.totalSegments()).isEqualTo(4);

        assertThat(plan.items().get(0).action()).isEqualTo(ChapterNarrationGenerationAction.SKIP_READY);
        assertThat(plan.items().get(1).action()).isEqualTo(ChapterNarrationGenerationAction.REGENERATE);
        assertThat(plan.items().get(2).action()).isEqualTo(ChapterNarrationGenerationAction.GENERATE);
        assertThat(plan.items().get(3).action()).isEqualTo(ChapterNarrationGenerationAction.GENERATE);

        assertThat(plan.readySkippedCount()).isEqualTo(1);
        assertThat(plan.generationRequestedCount()).isEqualTo(2);
        assertThat(plan.regenerationRequestedCount()).isEqualTo(1);
        assertThat(plan.workRequiredCount()).isEqualTo(3);
        assertThat(plan.hasWorkRequired()).isTrue();
    }

    @Test
    @DisplayName("6. Unsorted input snapshots are returned in strict segmentIndex ASC order")
    void shouldSortInputBySegmentIndexAscending() {
        List<ChapterNarrationSegmentHealthSnapshot> unsorted = List.of(
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_4, 3, ChapterNarrationAudioHealthStatus.FAILED),
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, 0, ChapterNarrationAudioHealthStatus.READY),
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_3, 2, ChapterNarrationAudioHealthStatus.MISSING),
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_2, 1, ChapterNarrationAudioHealthStatus.OUTDATED)
        );

        ChapterNarrationGenerationPlan plan = planner.plan(CHAPTER_ID, VOICE_ID, unsorted);

        assertThat(plan.items()).hasSize(4);
        assertThat(plan.items().get(0).segmentIndex()).isEqualTo(0);
        assertThat(plan.items().get(0).segmentId()).isEqualTo(SEGMENT_1);

        assertThat(plan.items().get(1).segmentIndex()).isEqualTo(1);
        assertThat(plan.items().get(1).segmentId()).isEqualTo(SEGMENT_2);

        assertThat(plan.items().get(2).segmentIndex()).isEqualTo(2);
        assertThat(plan.items().get(2).segmentId()).isEqualTo(SEGMENT_3);

        assertThat(plan.items().get(3).segmentIndex()).isEqualTo(3);
        assertThat(plan.items().get(3).segmentId()).isEqualTo(SEGMENT_4);
    }

    @Test
    @DisplayName("7. Empty CURRENT segment list produces an empty zero-count plan")
    void shouldReturnEmptyPlanForEmptyInput() {
        ChapterNarrationGenerationPlan plan = planner.plan(CHAPTER_ID, VOICE_ID, Collections.emptyList());

        assertThat(plan.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(plan.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(plan.items()).isEmpty();
        assertThat(plan.totalSegments()).isEqualTo(0);
        assertThat(plan.readySkippedCount()).isEqualTo(0);
        assertThat(plan.generationRequestedCount()).isEqualTo(0);
        assertThat(plan.regenerationRequestedCount()).isEqualTo(0);
        assertThat(plan.workRequiredCount()).isEqualTo(0);
        assertThat(plan.hasWorkRequired()).isFalse();
        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("8. Rejects duplicate segment ID in input")
    void shouldRejectDuplicateSegmentId() {
        List<ChapterNarrationSegmentHealthSnapshot> duplicates = List.of(
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, 0, ChapterNarrationAudioHealthStatus.READY),
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, 1, ChapterNarrationAudioHealthStatus.MISSING)
        );

        assertThatThrownBy(() -> planner.plan(CHAPTER_ID, VOICE_ID, duplicates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate segment ID");
    }

    @Test
    @DisplayName("9. Rejects duplicate segment index in input")
    void shouldRejectDuplicateSegmentIndex() {
        List<ChapterNarrationSegmentHealthSnapshot> duplicates = List.of(
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, 0, ChapterNarrationAudioHealthStatus.READY),
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_2, 0, ChapterNarrationAudioHealthStatus.MISSING)
        );

        assertThatThrownBy(() -> planner.plan(CHAPTER_ID, VOICE_ID, duplicates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate segment index");
    }

    @Test
    @DisplayName("10. Rejects null or invalid input parameters")
    void shouldRejectNullOrInvalidInputs() {
        List<ChapterNarrationSegmentHealthSnapshot> validList = List.of(
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, 0, ChapterNarrationAudioHealthStatus.READY)
        );

        assertThatThrownBy(() -> planner.plan(null, VOICE_ID, validList))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapterId must not be null");

        assertThatThrownBy(() -> planner.plan(CHAPTER_ID, null, validList))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("managedVoiceId must not be null");

        assertThatThrownBy(() -> planner.plan(CHAPTER_ID, VOICE_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("segmentSnapshots must not be null");

        List<ChapterNarrationSegmentHealthSnapshot> listWithNull = new ArrayList<>();
        listWithNull.add(null);
        assertThatThrownBy(() -> planner.plan(CHAPTER_ID, VOICE_ID, listWithNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("segmentSnapshots must not contain null elements");

        assertThatThrownBy(() -> new ChapterNarrationSegmentHealthSnapshot(null, 0, ChapterNarrationAudioHealthStatus.READY))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, -1, ChapterNarrationAudioHealthStatus.READY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("segmentIndex must be non-negative");

        assertThatThrownBy(() -> new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("11. Returned plan item list is immutable")
    void shouldReturnImmutableItemList() {
        ChapterNarrationSegmentHealthSnapshot snapshot =
                new ChapterNarrationSegmentHealthSnapshot(SEGMENT_1, 0, ChapterNarrationAudioHealthStatus.READY);

        ChapterNarrationGenerationPlan plan = planner.plan(CHAPTER_ID, VOICE_ID, List.of(snapshot));

        ChapterNarrationGenerationPlanItem newItem = new ChapterNarrationGenerationPlanItem(
                SEGMENT_2, 1, ChapterNarrationAudioHealthStatus.MISSING, ChapterNarrationGenerationAction.GENERATE
        );

        assertThatThrownBy(() -> plan.items().add(newItem))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
