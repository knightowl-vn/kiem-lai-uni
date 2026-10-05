package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCommunityPostEngagementCountsUseCase Unit Tests")
class GetCommunityPostEngagementCountsUseCaseTest {

    @Mock
    private ReactionRepositoryPort reactionRepositoryPort;

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    private GetCommunityPostEngagementCountsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetCommunityPostEngagementCountsUseCase(reactionRepositoryPort, commentRepositoryPort);
    }

    @Test
    @DisplayName("Should return empty map on null or empty postIds")
    void shouldReturnEmptyOnEmptyInput() {
        assertThat(useCase.getEngagementCountsForPosts(null)).isEmpty();
        assertThat(useCase.getEngagementCountsForPosts(List.of())).isEmpty();
        assertThat(useCase.getEngagementCountsForPosts(List.of(), UUID.randomUUID())).isEmpty();
        verifyNoInteractions(reactionRepositoryPort, commentRepositoryPort);
    }

    @Test
    @DisplayName("Should return counts with null user reaction when viewerUserId is null")
    void shouldReturnCountsWithoutUserReactionWhenGuest() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        when(reactionRepositoryPort.countTotalReactionsByTargetIds(eq(ReactionTargetType.COMMUNITY_POST), any()))
                .thenReturn(Map.of(p1, 5L, p2, 10L));
        when(commentRepositoryPort.countActiveCommentsByTargetIds(eq(CommentTargetType.COMMUNITY_POST), any()))
                .thenReturn(Map.of(p1, 2L, p2, 0L));

        Map<UUID, CommunityPostEngagementCountsDTO> result = useCase.getEngagementCountsForPosts(List.of(p1, p2));

        assertThat(result).hasSize(2);
        assertThat(result.get(p1).reactionCount()).isEqualTo(5L);
        assertThat(result.get(p1).commentCount()).isEqualTo(2L);
        assertThat(result.get(p1).currentUserReaction()).isNull();

        assertThat(result.get(p2).reactionCount()).isEqualTo(10L);
        assertThat(result.get(p2).commentCount()).isEqualTo(0L);
        assertThat(result.get(p2).currentUserReaction()).isNull();
    }

    @Test
    @DisplayName("Should return counts and user reactions in single batch query when viewerUserId is provided")
    void shouldIncludeUserReactionWhenViewerProvided() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();

        when(reactionRepositoryPort.countTotalReactionsByTargetIds(eq(ReactionTargetType.COMMUNITY_POST), any()))
                .thenReturn(Map.of(p1, 1L, p2, 0L));
        when(commentRepositoryPort.countActiveCommentsByTargetIds(eq(CommentTargetType.COMMUNITY_POST), any()))
                .thenReturn(Map.of(p1, 3L, p2, 1L));
        when(reactionRepositoryPort.findUserReactionsForTargetIds(eq(viewer), eq(ReactionTargetType.COMMUNITY_POST), any()))
                .thenReturn(Map.of(p1, ReactionType.LIKE));

        Map<UUID, CommunityPostEngagementCountsDTO> result = useCase.getEngagementCountsForPosts(List.of(p1, p2), viewer);

        assertThat(result).hasSize(2);
        assertThat(result.get(p1).reactionCount()).isEqualTo(1L);
        assertThat(result.get(p1).currentUserReaction()).isEqualTo("LIKE");

        assertThat(result.get(p2).reactionCount()).isEqualTo(0L);
        assertThat(result.get(p2).currentUserReaction()).isNull();

        verify(reactionRepositoryPort).findUserReactionsForTargetIds(eq(viewer), eq(ReactionTargetType.COMMUNITY_POST), any());
    }
}
