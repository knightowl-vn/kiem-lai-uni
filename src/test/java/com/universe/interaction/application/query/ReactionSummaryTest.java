package com.universe.interaction.application.query;

import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReactionSummary Unit Tests")
class ReactionSummaryTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Nested
    @DisplayName("1. Constructor & Invariant Validation")
    class InvariantValidationTests {

        @Test
        @DisplayName("1 & 2. Missing reaction keys normalize to zero, and all five keys are present")
        void shouldNormalizeMissingKeysToZeroAndIncludeAllFourKeys() {
            ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
            Map<ReactionType, Long> sparse = Map.of(ReactionType.LOVE, 5L);

            ReactionSummary summary = ReactionSummary.of(target, sparse, null);

            assertThat(summary.counts()).hasSize(5)
                    .containsEntry(ReactionType.LIKE, 0L)
                    .containsEntry(ReactionType.LOVE, 5L)
                    .containsEntry(ReactionType.FIRE, 0L)
                    .containsEntry(ReactionType.HAHA, 0L)
                    .containsEntry(ReactionType.SAD, 0L);
            assertThat(summary.totalCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("3 & 7. Valid zero and all-positive summary accepted; totalCount equals sum of normalized counts")
        void shouldAcceptValidSummaryAndMatchTotalCount() {
            ReactionTarget target = ReactionTarget.comment(TARGET_ID);
            Map<ReactionType, Long> counts = new EnumMap<>(ReactionType.class);
            counts.put(ReactionType.LIKE, 4L);
            counts.put(ReactionType.LOVE, 10L);
            counts.put(ReactionType.FIRE, 2L);
            counts.put(ReactionType.HAHA, 3L);
            counts.put(ReactionType.SAD, 1L);

            ReactionSummary summary = ReactionSummary.of(target, counts, ReactionType.LOVE);

            assertThat(summary.totalCount()).isEqualTo(20L);
            assertThat(summary.currentUserReaction()).isEqualTo(ReactionType.LOVE);
            assertThat(summary.counts())
                    .containsEntry(ReactionType.LIKE, 4L)
                    .containsEntry(ReactionType.LOVE, 10L)
                    .containsEntry(ReactionType.FIRE, 2L)
                    .containsEntry(ReactionType.HAHA, 3L)
                    .containsEntry(ReactionType.SAD, 1L);
        }

        @Test
        @DisplayName("4. Negative LOVE count is rejected with IllegalArgumentException")
        void shouldRejectNegativeCount() {
            ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
            Map<ReactionType, Long> countsWithNegative = new EnumMap<>(ReactionType.class);
            countsWithNegative.put(ReactionType.LOVE, -1L);

            assertThatThrownBy(() -> ReactionSummary.of(target, countsWithNegative, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be negative");

            assertThatThrownBy(() -> new ReactionSummary(target, countsWithNegative, -1L, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be negative");
        }

        @Test
        @DisplayName("5. Explicit null reaction count is rejected with IllegalArgumentException")
        void shouldRejectExplicitNullCount() {
            ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
            Map<ReactionType, Long> countsWithNull = new HashMap<>();
            countsWithNull.put(ReactionType.FIRE, null);

            assertThatThrownBy(() -> ReactionSummary.of(target, countsWithNull, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be null");

            assertThatThrownBy(() -> new ReactionSummary(target, countsWithNull, 0L, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be null");
        }

        @Test
        @DisplayName("6. Inconsistent direct-constructor totalCount is rejected with IllegalArgumentException")
        void shouldRejectInconsistentTotalCountInDirectConstructor() {
            ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
            Map<ReactionType, Long> counts = Map.of(ReactionType.LOVE, 5L);

            // True sum is 5, but passed totalCount is 999
            assertThatThrownBy(() -> new ReactionSummary(target, counts, 999L, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match sum of individual reaction counts");
        }

        @Test
        @DisplayName("8. Returned count map is immutable")
        void shouldReturnImmutableCountsMap() {
            ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
            ReactionSummary summary = ReactionSummary.of(target, Map.of(ReactionType.LOVE, 2L), null);

            assertThatThrownBy(() -> summary.counts().put(ReactionType.FIRE, 10L))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Null key in counts map is rejected with IllegalArgumentException")
        void shouldRejectNullKeyInCountsMap() {
            ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
            Map<ReactionType, Long> countsWithNullKey = new HashMap<>();
            countsWithNullKey.put(null, 5L);

            assertThatThrownBy(() -> ReactionSummary.of(target, countsWithNullKey, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot contain null keys");

            assertThatThrownBy(() -> new ReactionSummary(target, countsWithNullKey, 5L, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot contain null keys");
        }
    }
}
