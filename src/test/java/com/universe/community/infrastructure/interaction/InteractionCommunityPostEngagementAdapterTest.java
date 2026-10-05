package com.universe.community.infrastructure.interaction;

import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort;
import com.universe.interaction.application.ports.CommunityPostEngagementQueryPort;
import com.universe.interaction.application.query.CommunityPostEngagementCountsDTO;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InteractionCommunityPostEngagementAdapter Unit Tests")
class InteractionCommunityPostEngagementAdapterTest {

    @Mock
    private CommunityPostEngagementQueryPort interactionQueryPort;

    private InteractionCommunityPostEngagementAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new InteractionCommunityPostEngagementAdapter(interactionQueryPort);
    }

    @Test
    @DisplayName("Should return empty map on null or empty post IDs")
    void shouldReturnEmptyMapOnEmptyInput() {
        assertThat(adapter.getEngagementMetricsForPosts(null)).isEmpty();
        assertThat(adapter.getEngagementMetricsForPosts(List.of())).isEmpty();
        verifyNoInteractions(interactionQueryPort);
    }

    @Test
    @DisplayName("Should delegate to Interaction port and map DTOs to Community engagement metrics")
    void shouldDelegateAndMapMetrics() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        when(interactionQueryPort.getEngagementCountsForPosts(List.of(p1, p2), null)).thenReturn(Map.of(
                p1, new CommunityPostEngagementCountsDTO(p1, 15L, 4L),
                p2, new CommunityPostEngagementCountsDTO(p2, 0L, 2L)
        ));

        Map<UUID, CommunityPostEngagementMetricsPort.PostEngagementMetrics> metrics =
                adapter.getEngagementMetricsForPosts(List.of(p1, p2));

        assertThat(metrics).hasSize(2);
        assertThat(metrics.get(p1).reactionCount()).isEqualTo(15L);
        assertThat(metrics.get(p1).commentCount()).isEqualTo(4L);

        assertThat(metrics.get(p2).reactionCount()).isEqualTo(0L);
        assertThat(metrics.get(p2).commentCount()).isEqualTo(2L);

        verify(interactionQueryPort).getEngagementCountsForPosts(List.of(p1, p2), null);
    }

    @Test
    @DisplayName("Should delegate to 2-arg Interaction port when viewerUserId is provided")
    void shouldDelegateWithViewerUserId() {
        UUID p1 = UUID.randomUUID();
        UUID viewerUserId = UUID.randomUUID();

        when(interactionQueryPort.getEngagementCountsForPosts(List.of(p1), viewerUserId)).thenReturn(Map.of(
                p1, new CommunityPostEngagementCountsDTO(p1, 7L, 3L, "LIKE")
        ));

        Map<UUID, CommunityPostEngagementMetricsPort.PostEngagementMetrics> metrics =
                adapter.getEngagementMetricsForPosts(List.of(p1), viewerUserId);

        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(p1).reactionCount()).isEqualTo(7L);
        assertThat(metrics.get(p1).commentCount()).isEqualTo(3L);
        assertThat(metrics.get(p1).currentUserReaction()).isEqualTo("LIKE");

        verify(interactionQueryPort).getEngagementCountsForPosts(List.of(p1), viewerUserId);
    }

    @Test
    @DisplayName("Should pass null viewerUserId to authoritative 2-arg port method when viewerUserId is null")
    void shouldPassNullViewerUserIdToAuthoritativePort() {
        UUID p1 = UUID.randomUUID();

        when(interactionQueryPort.getEngagementCountsForPosts(List.of(p1), null)).thenReturn(Map.of(
                p1, new CommunityPostEngagementCountsDTO(p1, 2L, 0L)
        ));

        Map<UUID, CommunityPostEngagementMetricsPort.PostEngagementMetrics> metrics =
                adapter.getEngagementMetricsForPosts(List.of(p1), null);

        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(p1).reactionCount()).isEqualTo(2L);
        assertThat(metrics.get(p1).currentUserReaction()).isNull();

        verify(interactionQueryPort).getEngagementCountsForPosts(List.of(p1), null);
    }

    @Test
    @DisplayName("CommunityPostEngagementMetricsPort default method delegates to 2-arg method with null viewerUserId")
    void metricsPortDefaultMethodDelegatesWithNullViewer() {
        UUID postId = UUID.randomUUID();
        CommunityPostEngagementMetricsPort.PostEngagementMetrics expected =
                new CommunityPostEngagementMetricsPort.PostEngagementMetrics(10L, 2L, null);

        CommunityPostEngagementMetricsPort customPort = (postIds, viewerUserId) -> {
            assertThat(viewerUserId).isNull();
            return Map.of(postId, expected);
        };

        Map<UUID, CommunityPostEngagementMetricsPort.PostEngagementMetrics> result =
                customPort.getEngagementMetricsForPosts(List.of(postId));

        assertThat(result).hasSize(1);
        assertThat(result.get(postId)).isEqualTo(expected);
    }

    @Test
    @DisplayName("CommunityPostEngagementQueryPort default method delegates to 2-arg method with null viewerUserId")
    void queryPortDefaultMethodDelegatesWithNullViewer() {
        UUID postId = UUID.randomUUID();
        CommunityPostEngagementCountsDTO expected =
                new CommunityPostEngagementCountsDTO(postId, 5L, 1L, null);

        CommunityPostEngagementQueryPort customPort = (postIds, viewerUserId) -> {
            assertThat(viewerUserId).isNull();
            return Map.of(postId, expected);
        };

        Map<UUID, CommunityPostEngagementCountsDTO> result =
                customPort.getEngagementCountsForPosts(List.of(postId));

        assertThat(result).hasSize(1);
        assertThat(result.get(postId)).isEqualTo(expected);
    }
}
