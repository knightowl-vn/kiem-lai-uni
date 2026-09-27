package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.ReactionTargetNotEligibleException;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.application.ports.ReactionTargetEligibilityPort;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetReactionSummaryUseCase Unit Tests")
class GetReactionSummaryUseCaseTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private ReactionRepositoryPort reactionRepositoryPort;

    @Mock
    private ReactionTargetEligibilityPort eligibilityPort;

    private GetReactionSummaryUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetReactionSummaryUseCase(reactionRepositoryPort, eligibilityPort);
    }

    @Nested
    @DisplayName("1. Constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Throws NullPointerException when ReactionRepositoryPort is null")
        void shouldThrowWhenRepositoryIsNull() {
            assertThatThrownBy(() -> new GetReactionSummaryUseCase(null, eligibilityPort))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ReactionRepositoryPort");
        }

        @Test
        @DisplayName("Throws NullPointerException when ReactionTargetEligibilityPort is null")
        void shouldThrowWhenEligibilityPortIsNull() {
            assertThatThrownBy(() -> new GetReactionSummaryUseCase(reactionRepositoryPort, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ReactionTargetEligibilityPort");
        }
    }

    @Nested
    @DisplayName("2. Single target summary execution")
    class SingleTargetSummaryTests {

        @Test
        @DisplayName("Throws NullPointerException when target is null")
        void shouldThrowWhenTargetIsNull() {
            assertThatThrownBy(() -> useCase.execute(null, USER_ID))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ReactionTarget");
        }

        @Test
        @DisplayName("Throws ReactionTargetNotEligibleException when target is not eligible")
        void shouldThrowWhenTargetIsNotEligible() {
            ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
            when(eligibilityPort.isEligible(target)).thenReturn(false);

            assertThatThrownBy(() -> useCase.execute(target, USER_ID))
                    .isInstanceOf(ReactionTargetNotEligibleException.class)
                    .satisfies(ex -> assertThat(((ReactionTargetNotEligibleException) ex).getTarget()).isEqualTo(target));

            verify(reactionRepositoryPort, never()).countReactionsByTargetGroupedByType(any());
            verify(reactionRepositoryPort, never()).findUserReactionType(any(), any());
        }

        @Test
        @DisplayName("Returns summary for anonymous viewer (userId is null)")
        void shouldReturnSummaryForAnonymousViewer() {
            ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
            when(eligibilityPort.isEligible(target)).thenReturn(true);

            Map<ReactionType, Long> counts = new EnumMap<>(ReactionType.class);
            counts.put(ReactionType.LIKE, 3L);
            counts.put(ReactionType.LOVE, 5L);
            counts.put(ReactionType.FIRE, 2L);
            counts.put(ReactionType.HAHA, 0L);
            counts.put(ReactionType.SAD, 1L);
            when(reactionRepositoryPort.countReactionsByTargetGroupedByType(target)).thenReturn(counts);

            ReactionSummary summary = useCase.execute(target);

            assertThat(summary).isNotNull();
            assertThat(summary.target()).isEqualTo(target);
            assertThat(summary.counts()).hasSize(5)
                    .containsEntry(ReactionType.LIKE, 3L)
                    .containsEntry(ReactionType.LOVE, 5L)
                    .containsEntry(ReactionType.FIRE, 2L)
                    .containsEntry(ReactionType.HAHA, 0L)
                    .containsEntry(ReactionType.SAD, 1L);
            assertThat(summary.totalCount()).isEqualTo(11L);
            assertThat(summary.currentUserReaction()).isNull();

            verify(reactionRepositoryPort, never()).findUserReactionType(any(), any());
        }

        @Test
        @DisplayName("Returns summary with active reaction for authenticated viewer")
        void shouldReturnSummaryWithActiveReactionForAuthenticatedViewer() {
            ReactionTarget target = ReactionTarget.comment(TARGET_ID);
            when(eligibilityPort.isEligible(target)).thenReturn(true);

            Map<ReactionType, Long> counts = new EnumMap<>(ReactionType.class);
            counts.put(ReactionType.LIKE, 0L);
            counts.put(ReactionType.LOVE, 10L);
            counts.put(ReactionType.FIRE, 0L);
            counts.put(ReactionType.HAHA, 3L);
            counts.put(ReactionType.SAD, 0L);
            when(reactionRepositoryPort.countReactionsByTargetGroupedByType(target)).thenReturn(counts);
            when(reactionRepositoryPort.findUserReactionType(USER_ID, target))
                    .thenReturn(Optional.of(ReactionType.LOVE));

            ReactionSummary summary = useCase.execute(target, USER_ID);

            assertThat(summary).isNotNull();
            assertThat(summary.target()).isEqualTo(target);
            assertThat(summary.counts()).hasSize(5)
                    .containsEntry(ReactionType.LIKE, 0L)
                    .containsEntry(ReactionType.LOVE, 10L)
                    .containsEntry(ReactionType.FIRE, 0L)
                    .containsEntry(ReactionType.HAHA, 3L)
                    .containsEntry(ReactionType.SAD, 0L);
            assertThat(summary.totalCount()).isEqualTo(13L);
            assertThat(summary.currentUserReaction()).isEqualTo(ReactionType.LOVE);
        }

        @Test
        @DisplayName("Returns summary with null reaction when authenticated viewer has not reacted")
        void shouldReturnSummaryWithNullReactionWhenUserHasNotReacted() {
            ReactionTarget target = ReactionTarget.comment(TARGET_ID);
            when(eligibilityPort.isEligible(target)).thenReturn(true);

            Map<ReactionType, Long> counts = new EnumMap<>(ReactionType.class);
            counts.put(ReactionType.LIKE, 0L);
            counts.put(ReactionType.LOVE, 0L);
            counts.put(ReactionType.FIRE, 0L);
            counts.put(ReactionType.HAHA, 0L);
            counts.put(ReactionType.SAD, 0L);
            when(reactionRepositoryPort.countReactionsByTargetGroupedByType(target)).thenReturn(counts);
            when(reactionRepositoryPort.findUserReactionType(USER_ID, target))
                    .thenReturn(Optional.empty());

            ReactionSummary summary = useCase.execute(target, USER_ID);

            assertThat(summary).isNotNull();
            assertThat(summary.target()).isEqualTo(target);
            assertThat(summary.totalCount()).isEqualTo(0L);
            assertThat(summary.currentUserReaction()).isNull();
        }

        @Test
        @DisplayName("Defensively fills missing enum types with 0 count")
        void shouldDefensivelyNormalizeMissingCounts() {
            ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
            when(eligibilityPort.isEligible(target)).thenReturn(true);

            // Repository returns incomplete map
            Map<ReactionType, Long> sparseCounts = Map.of(ReactionType.FIRE, 3L);
            when(reactionRepositoryPort.countReactionsByTargetGroupedByType(target)).thenReturn(sparseCounts);

            ReactionSummary summary = useCase.execute(target, null);

            assertThat(summary.counts()).hasSize(5)
                    .containsEntry(ReactionType.LIKE, 0L)
                    .containsEntry(ReactionType.LOVE, 0L)
                    .containsEntry(ReactionType.FIRE, 3L)
                    .containsEntry(ReactionType.HAHA, 0L)
                    .containsEntry(ReactionType.SAD, 0L);
            assertThat(summary.totalCount()).isEqualTo(3L);
        }
    }
}
