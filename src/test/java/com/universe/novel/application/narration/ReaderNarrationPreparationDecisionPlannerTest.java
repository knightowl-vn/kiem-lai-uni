package com.universe.novel.application.narration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReaderNarrationPreparationDecisionPlanner Unit Tests (MS-04.9H.7C2A)")
class ReaderNarrationPreparationDecisionPlannerTest {

    private static final UUID SEGMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int SEGMENT_INDEX = 0;

    private ReaderNarrationPreparationDecisionPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new ReaderNarrationPreparationDecisionPlanner();
    }

    @Nested
    @DisplayName("1-4. Action Mapping Tests")
    class ActionMappingTests {

        @Test
        @DisplayName("1. READY -> PLAY_NOW")
        void readyMapsToPlayNow() {
            ReaderNarrationPreparationDecision decision = planner.plan(
                    SEGMENT_ID, SEGMENT_INDEX, ChapterNarrationAudioHealthStatus.READY
            );

            assertThat(decision.segmentId()).isEqualTo(SEGMENT_ID);
            assertThat(decision.segmentIndex()).isEqualTo(SEGMENT_INDEX);
            assertThat(decision.health()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
            assertThat(decision.action()).isEqualTo(ReaderNarrationPreparationAction.PLAY_NOW);
        }

        @Test
        @DisplayName("2. OUTDATED -> PLAY_NOW_AND_REFRESH")
        void outdatedMapsToPlayNowAndRefresh() {
            ReaderNarrationPreparationDecision decision = planner.plan(
                    SEGMENT_ID, SEGMENT_INDEX, ChapterNarrationAudioHealthStatus.OUTDATED
            );

            assertThat(decision.segmentId()).isEqualTo(SEGMENT_ID);
            assertThat(decision.segmentIndex()).isEqualTo(SEGMENT_INDEX);
            assertThat(decision.health()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
            assertThat(decision.action()).isEqualTo(ReaderNarrationPreparationAction.PLAY_NOW_AND_REFRESH);
        }

        @Test
        @DisplayName("3. MISSING -> PREPARE")
        void missingMapsToPrepare() {
            ReaderNarrationPreparationDecision decision = planner.plan(
                    SEGMENT_ID, SEGMENT_INDEX, ChapterNarrationAudioHealthStatus.MISSING
            );

            assertThat(decision.segmentId()).isEqualTo(SEGMENT_ID);
            assertThat(decision.segmentIndex()).isEqualTo(SEGMENT_INDEX);
            assertThat(decision.health()).isEqualTo(ChapterNarrationAudioHealthStatus.MISSING);
            assertThat(decision.action()).isEqualTo(ReaderNarrationPreparationAction.PREPARE);
        }

        @Test
        @DisplayName("4. FAILED -> RETRY_PREPARE")
        void failedMapsToRetryPrepare() {
            ReaderNarrationPreparationDecision decision = planner.plan(
                    SEGMENT_ID, SEGMENT_INDEX, ChapterNarrationAudioHealthStatus.FAILED
            );

            assertThat(decision.segmentId()).isEqualTo(SEGMENT_ID);
            assertThat(decision.segmentIndex()).isEqualTo(SEGMENT_INDEX);
            assertThat(decision.health()).isEqualTo(ChapterNarrationAudioHealthStatus.FAILED);
            assertThat(decision.action()).isEqualTo(ReaderNarrationPreparationAction.RETRY_PREPARE);
        }
    }

    @Nested
    @DisplayName("5-8. Derived Helper Semantics Tests")
    class HelperSemanticsTests {

        @Test
        @DisplayName("5. READY is playable now, does not require preparation, does not recommend refresh, and does not block playback")
        void readyHelperSemantics() {
            ReaderNarrationPreparationDecision decision = planner.plan(
                    SEGMENT_ID, SEGMENT_INDEX, ChapterNarrationAudioHealthStatus.READY
            );

            assertThat(decision.isPlayableNow()).isTrue();
            assertThat(decision.requiresImmediatePreparation()).isFalse();
            assertThat(decision.recommendsRefresh()).isFalse();
            assertThat(decision.blocksPlayback()).isFalse();
        }

        @Test
        @DisplayName("6. OUTDATED is playable now, does not require preparation, recommends refresh, and does not block playback")
        void outdatedHelperSemantics() {
            ReaderNarrationPreparationDecision decision = planner.plan(
                    SEGMENT_ID, SEGMENT_INDEX, ChapterNarrationAudioHealthStatus.OUTDATED
            );

            assertThat(decision.isPlayableNow()).isTrue();
            assertThat(decision.requiresImmediatePreparation()).isFalse();
            assertThat(decision.recommendsRefresh()).isTrue();
            assertThat(decision.blocksPlayback()).isFalse();
        }

        @Test
        @DisplayName("7. MISSING is not playable now, requires immediate preparation, does not recommend refresh, and blocks playback")
        void missingHelperSemantics() {
            ReaderNarrationPreparationDecision decision = planner.plan(
                    SEGMENT_ID, SEGMENT_INDEX, ChapterNarrationAudioHealthStatus.MISSING
            );

            assertThat(decision.isPlayableNow()).isFalse();
            assertThat(decision.requiresImmediatePreparation()).isTrue();
            assertThat(decision.recommendsRefresh()).isFalse();
            assertThat(decision.blocksPlayback()).isTrue();
        }

        @Test
        @DisplayName("8. FAILED is not playable now, requires immediate retry preparation, does not recommend refresh, and blocks playback")
        void failedHelperSemantics() {
            ReaderNarrationPreparationDecision decision = planner.plan(
                    SEGMENT_ID, SEGMENT_INDEX, ChapterNarrationAudioHealthStatus.FAILED
            );

            assertThat(decision.isPlayableNow()).isFalse();
            assertThat(decision.requiresImmediatePreparation()).isTrue();
            assertThat(decision.recommendsRefresh()).isFalse();
            assertThat(decision.blocksPlayback()).isTrue();
        }
    }

    @Nested
    @DisplayName("9. Input Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Planner rejects null segmentId")
        void plannerRejectsNullSegmentId() {
            assertThatThrownBy(() -> planner.plan(null, SEGMENT_INDEX, ChapterNarrationAudioHealthStatus.READY))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("segmentId must not be null");
        }

        @Test
        @DisplayName("Planner rejects negative segmentIndex")
        void plannerRejectsNegativeSegmentIndex() {
            assertThatThrownBy(() -> planner.plan(SEGMENT_ID, -1, ChapterNarrationAudioHealthStatus.READY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("segmentIndex must not be negative: -1");
        }

        @Test
        @DisplayName("Planner rejects null health")
        void plannerRejectsNullHealth() {
            assertThatThrownBy(() -> planner.plan(SEGMENT_ID, SEGMENT_INDEX, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("health must not be null");
        }

        @Test
        @DisplayName("resolveAction rejects null health")
        void resolveActionRejectsNullHealth() {
            assertThatThrownBy(() -> planner.resolveAction(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("health must not be null");
        }

        @Test
        @DisplayName("Decision record constructor rejects null action")
        void decisionRejectsNullAction() {
            assertThatThrownBy(() -> new ReaderNarrationPreparationDecision(
                    SEGMENT_ID, SEGMENT_INDEX, ChapterNarrationAudioHealthStatus.READY, null
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("action must not be null");
        }
    }

    @Nested
    @DisplayName("10. Determinism & Statelessness Tests")
    class DeterminismTests {

        @Test
        @DisplayName("10. Planner produces identical deterministic decisions across repeated calls without side effects")
        void plannerIsDeterministic() {
            ReaderNarrationPreparationDecision d1 = planner.plan(
                    SEGMENT_ID, SEGMENT_INDEX, ChapterNarrationAudioHealthStatus.OUTDATED
            );
            ReaderNarrationPreparationDecision d2 = planner.plan(
                    SEGMENT_ID, SEGMENT_INDEX, ChapterNarrationAudioHealthStatus.OUTDATED
            );

            assertThat(d1).isEqualTo(d2);
            assertThat(d1.hashCode()).isEqualTo(d2.hashCode());
            assertThat(d1.toString()).isEqualTo(d2.toString());
        }
    }
}
