package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetBatchReactionSummariesUseCase Unit Tests")
class GetBatchReactionSummariesUseCaseTest {

    private static final UUID TARGET_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private ReactionRepositoryPort reactionRepositoryPort;

    private GetBatchReactionSummariesUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetBatchReactionSummariesUseCase(reactionRepositoryPort);
    }

    @Nested
    @DisplayName("1. Constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Throws NullPointerException when ReactionRepositoryPort is null")
        void shouldThrowWhenRepositoryIsNull() {
            assertThatThrownBy(() -> new GetBatchReactionSummariesUseCase(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ReactionRepositoryPort");
        }
    }

    @Nested
    @DisplayName("2. Batch query execution")
    class BatchQueryExecutionTests {

        @Test
        @DisplayName("Throws NullPointerException when targetType is null")
        void shouldThrowWhenTargetTypeIsNull() {
            assertThatThrownBy(() -> useCase.execute(null, List.of(TARGET_1), USER_ID))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ReactionTargetType");
        }

        @Test
        @DisplayName("Returns empty map without DB calls when targetIds is null or empty")
        void shouldReturnEmptyMapWhenTargetIdsIsNullOrEmpty() {
            Map<UUID, ReactionSummary> fromNull = useCase.execute(ReactionTargetType.COMMENT, null, USER_ID);
            Map<UUID, ReactionSummary> fromEmpty = useCase.execute(ReactionTargetType.COMMENT, Collections.emptyList(), USER_ID);

            assertThat(fromNull).isEmpty();
            assertThat(fromEmpty).isEmpty();
            verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());
            verify(reactionRepositoryPort, never()).findUserReactionsForTargetIds(any(), any(), any());
        }

        @Test
        @DisplayName("Returns empty map without DB calls when targetIds contains only nulls")
        void shouldReturnEmptyMapWhenTargetIdsContainsOnlyNulls() {
            List<UUID> onlyNulls = Collections.singletonList(null);
            Map<UUID, ReactionSummary> result = useCase.execute(ReactionTargetType.COMMENT, onlyNulls, USER_ID);

            assertThat(result).isEmpty();
            verify(reactionRepositoryPort, never()).countReactionsByTargetIdsGroupedByType(any(), any());
        }

        @Test
        @DisplayName("Returns batch summaries for anonymous viewer (single DB query for counts)")
        void shouldReturnBatchSummariesForAnonymousViewer() {
            Map<ReactionType, Long> counts1 = new EnumMap<>(ReactionType.class);
            counts1.put(ReactionType.LOVE, 3L);
            counts1.put(ReactionType.FIRE, 1L);
            counts1.put(ReactionType.HAHA, 0L);
            counts1.put(ReactionType.SAD, 0L);

            Map<ReactionType, Long> counts2 = new EnumMap<>(ReactionType.class);
            counts2.put(ReactionType.LOVE, 0L);
            counts2.put(ReactionType.FIRE, 0L);
            counts2.put(ReactionType.HAHA, 0L);
            counts2.put(ReactionType.SAD, 0L);

            when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(
                    ReactionTargetType.COMMENT,
                    Set.of(TARGET_1, TARGET_2)
            )).thenReturn(Map.of(
                    TARGET_1, counts1,
                    TARGET_2, counts2
            ));

            Map<UUID, ReactionSummary> summaries = useCase.execute(
                    ReactionTargetType.COMMENT,
                    List.of(TARGET_1, TARGET_2, TARGET_1) // Includes duplicate TARGET_1
            );

            assertThat(summaries).hasSize(2);
            assertThat(summaries.get(TARGET_1).totalCount()).isEqualTo(4L);
            assertThat(summaries.get(TARGET_1).currentUserReaction()).isNull();
            assertThat(summaries.get(TARGET_2).totalCount()).isEqualTo(0L);
            assertThat(summaries.get(TARGET_2).currentUserReaction()).isNull();

            verify(reactionRepositoryPort, never()).findUserReactionsForTargetIds(any(), any(), any());
        }

        @Test
        @DisplayName("Returns batch summaries with user reactions for authenticated viewer")
        void shouldReturnBatchSummariesForAuthenticatedViewer() {
            Map<ReactionType, Long> counts1 = new EnumMap<>(ReactionType.class);
            counts1.put(ReactionType.LOVE, 5L);
            counts1.put(ReactionType.FIRE, 0L);
            counts1.put(ReactionType.HAHA, 0L);
            counts1.put(ReactionType.SAD, 0L);

            Map<ReactionType, Long> counts2 = new EnumMap<>(ReactionType.class);
            counts2.put(ReactionType.LOVE, 0L);
            counts2.put(ReactionType.FIRE, 2L);
            counts2.put(ReactionType.HAHA, 1L);
            counts2.put(ReactionType.SAD, 0L);

            when(reactionRepositoryPort.countReactionsByTargetIdsGroupedByType(
                    ReactionTargetType.NOVEL_CHAPTER,
                    Set.of(TARGET_1, TARGET_2)
            )).thenReturn(Map.of(
                    TARGET_1, counts1,
                    TARGET_2, counts2
            ));

            when(reactionRepositoryPort.findUserReactionsForTargetIds(
                    USER_ID,
                    ReactionTargetType.NOVEL_CHAPTER,
                    Set.of(TARGET_1, TARGET_2)
            )).thenReturn(Map.of(
                    TARGET_1, ReactionType.LOVE
                    // TARGET_2 has no reaction for this user
            ));

            Map<UUID, ReactionSummary> summaries = useCase.execute(
                    ReactionTargetType.NOVEL_CHAPTER,
                    List.of(TARGET_1, TARGET_2),
                    USER_ID
            );

            assertThat(summaries).hasSize(2);
            assertThat(summaries.get(TARGET_1).totalCount()).isEqualTo(5L);
            assertThat(summaries.get(TARGET_1).currentUserReaction()).isEqualTo(ReactionType.LOVE);
            assertThat(summaries.get(TARGET_2).totalCount()).isEqualTo(3L);
            assertThat(summaries.get(TARGET_2).currentUserReaction()).isNull();
        }
    }
}
